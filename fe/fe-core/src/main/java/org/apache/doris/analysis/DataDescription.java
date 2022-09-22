// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.analysis;

import org.apache.doris.analysis.BinaryPredicate.Operator;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.FunctionSet;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.Pair;
import org.apache.doris.common.util.SqlParserUtils;
import org.apache.doris.load.loadv2.LoadTask;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.thrift.TNetworkAddress;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

// used to describe data info which is needed to import.
//
//      data_desc:
//          DATA INFILE ('file_path', ...)
//          [NEGATIVE]
//          INTO TABLE tbl_name
//          [PARTITION (p1, p2)]
//          [COLUMNS TERMINATED BY separator]
//          [FORMAT AS format]
//          [(tmp_col1, tmp_col2, col3, ...)]
//          [COLUMNS FROM PATH AS (col1, ...)]
//          [SET (k1=f1(xx), k2=f2(xxx))]
//          [where_clause]
//
//          DATA FROM TABLE external_hive_tbl_name
//          [NEGATIVE]
//          INTO TABLE tbl_name
//          [PARTITION (p1, p2)]
//          [SET (k1=f1(xx), k2=f2(xxx))]
//          [where_clause]

/**
 * The transform of columns should be added after the keyword named COLUMNS.
 * The transform after the keyword named SET is the old ways which only supports the hadoop function.
 * It old way of transform will be removed gradually. It
 */
public class DataDescription {
    private static final Logger LOG = LogManager.getLogger(DataDescription.class);
    // function isn't built-in function, hll_hash is not built-in function in hadoop load.
    private static final List<String> HADOOP_SUPPORT_FUNCTION_NAMES = Arrays.asList(
            "strftime",
            "time_format",
            "alignment_timestamp",
            "default_value",
            "md5sum",
            "replace_value",
            "now",
            FunctionSet.HLL_HASH,
            "substitute");

    private final String tableName;
    private final PartitionNames partitionNames;
    private final List<String> filePaths;
    private final Separator columnSeparator;
    private final String fileFormat;
    private final boolean isNegative;
    // column names in the path
    private final List<String> columnsFromPath;
    // save column mapping in SET(xxx = xxx) clause
    private final List<Expr> columnMappingList;
    private final Expr precedingFilterExpr;
    private final Expr whereExpr;
    private final String srcTableName;
    // this only used in multi load, all filePaths is file not dir
    private List<Long> fileSize;
    // column names of source files
    private List<String> fileFieldNames;
    // Used for mini load
    private TNetworkAddress beAddr;
    private Separator lineDelimiter;
    private String columnDef;
    private long backendId;
    private boolean stripOuterArray = false;
    private String jsonPaths = "";
    private String jsonRoot = "";
    private boolean fuzzyParse = false;
    private boolean readJsonByLine = false;
    private boolean numAsString = false;

    private String sequenceCol;

    // Merged from fileFieldNames, columnsFromPath and columnMappingList
    // ImportColumnDesc: column name to (expr or null)
    private List<ImportColumnDesc> parsedColumnExprList = Lists.newArrayList();
    /*
     * This param only include the hadoop function which need to be checked in the future.
     * For hadoop load, this param is also used to persistence.
     * The function in this param is copied from 'parsedColumnExprList'
     */
    private final Map<String, Pair<String, List<String>>> columnToHadoopFunction
            = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);

    private boolean isHadoopLoad = false;

    private LoadTask.MergeType mergeType = LoadTask.MergeType.APPEND;
    private final Expr deleteCondition;
    private final Map<String, String> properties;

    public DataDescription(String tableName,
                           PartitionNames partitionNames,
                           List<String> filePaths,
                           List<String> columns,
                           Separator columnSeparator,
                           String fileFormat,
                           boolean isNegative,
                           List<Expr> columnMappingList) {
        this(tableName, partitionNames, filePaths, columns, columnSeparator, fileFormat, null,
                isNegative, columnMappingList, null, null, LoadTask.MergeType.APPEND, null, null, null);
    }

    public DataDescription(String tableName,
                           PartitionNames partitionNames,
                           List<String> filePaths,
                           List<String> columns,
                           Separator columnSeparator,
                           String fileFormat,
                           List<String> columnsFromPath,
                           boolean isNegative,
                           List<Expr> columnMappingList,
                           Expr fileFilterExpr,
                           Expr whereExpr,
                           LoadTask.MergeType mergeType,
                           Expr deleteCondition,
                           String sequenceColName,
                           Map<String, String> properties) {
        this.tableName = tableName;
        this.partitionNames = partitionNames;
        this.filePaths = filePaths;
        this.fileFieldNames = columns;
        this.columnSeparator = columnSeparator;
        this.fileFormat = fileFormat;
        this.columnsFromPath = columnsFromPath;
        this.isNegative = isNegative;
        this.columnMappingList = columnMappingList;
        this.precedingFilterExpr = fileFilterExpr;
        this.whereExpr = whereExpr;
        this.srcTableName = null;
        this.mergeType = mergeType;
        this.deleteCondition = deleteCondition;
        this.sequenceCol = sequenceColName;
        this.properties = properties;
    }

    // data from table external_hive_table
    public DataDescription(String tableName,
                           PartitionNames partitionNames,
                           String srcTableName,
                           boolean isNegative,
                           List<Expr> columnMappingList,
                           Expr whereExpr,
                           LoadTask.MergeType mergeType,
                           Expr deleteCondition,
                           Map<String, String> properties) {
        this.tableName = tableName;
        this.partitionNames = partitionNames;
        this.filePaths = null;
        this.fileFieldNames = null;
        this.columnSeparator = null;
        this.fileFormat = null;
        this.columnsFromPath = null;
        this.isNegative = isNegative;
        this.columnMappingList = columnMappingList;
        this.precedingFilterExpr = null; // external hive table does not support file filter expr
        this.whereExpr = whereExpr;
        this.srcTableName = srcTableName;
        this.mergeType = mergeType;
        this.deleteCondition = deleteCondition;
        this.properties = properties;
    }

    public static void validateMappingFunction(String functionName, List<String> args,
                                               Map<String, String> columnNameMap,
                                               Column mappingColumn, boolean isHadoopLoad) throws AnalysisException {
        if (functionName.equalsIgnoreCase("alignment_timestamp")) {
            validateAlignmentTimestamp(args, columnNameMap);
        } else if (functionName.equalsIgnoreCase("strftime")) {
            validateStrftime(args, columnNameMap);
        } else if (functionName.equalsIgnoreCase("time_format")) {
            validateTimeFormat(args, columnNameMap);
        } else if (functionName.equalsIgnoreCase("default_value")) {
            validateDefaultValue(args, mappingColumn);
        } else if (functionName.equalsIgnoreCase("md5sum")) {
            validateMd5sum(args, columnNameMap);
        } else if (functionName.equalsIgnoreCase("replace_value")) {
            validateReplaceValue(args, mappingColumn);
        } else if (functionName.equalsIgnoreCase(FunctionSet.HLL_HASH)) {
            validateHllHash(args, columnNameMap);
        } else if (functionName.equalsIgnoreCase("now")) {
            validateNowFunction(mappingColumn);
        } else if (functionName.equalsIgnoreCase("substitute")) {
            validateSubstituteFunction(args, columnNameMap);
        } else {
            if (isHadoopLoad) {
                throw new AnalysisException("Unknown function: " + functionName);
            }
        }
    }

    // eg: k2 = substitute(k1)
    // this is used for creating derivative column from existing column
    private static void validateSubstituteFunction(List<String> args, Map<String, String> columnNameMap)
            throws AnalysisException {
        if (args.size() != 1) {
            throw new AnalysisException("Should has only one argument: " + args);
        }

        String argColumn = args.get(0);
        if (!columnNameMap.containsKey(argColumn)) {
            throw new AnalysisException("Column is not in sources, column: " + argColumn);
        }

        args.set(0, columnNameMap.get(argColumn));
    }

    private static void validateAlignmentTimestamp(List<String> args, Map<String, String> columnNameMap)
            throws AnalysisException {
        if (args.size() != 2) {
            throw new AnalysisException("Function alignment_timestamp args size is not 2");
        }

        String precision = args.get(0).toLowerCase();
        String regex = "^year|month|day|hour$";
        if (!precision.matches(regex)) {
            throw new AnalysisException("Alignment precision error. regex: " + regex + ", arg: " + precision);
        }

        String argColumn = args.get(1);
        if (!columnNameMap.containsKey(argColumn)) {
            throw new AnalysisException("Column is not in sources, column: " + argColumn);
        }

        args.set(1, columnNameMap.get(argColumn));
    }

    private static void validateStrftime(List<String> args, Map<String, String> columnNameMap) throws
            AnalysisException {
        if (args.size() != 2) {
            throw new AnalysisException("Function strftime needs 2 args");
        }

        String format = args.get(0);
        String regex = "^(%[YMmdHhiSs][ -:]?){0,5}%[YMmdHhiSs]$";
        if (!format.matches(regex)) {
            throw new AnalysisException("Date format error. regex: " + regex + ", arg: " + format);
        }

        String argColumn = args.get(1);
        if (!columnNameMap.containsKey(argColumn)) {
            throw new AnalysisException("Column is not in sources, column: " + argColumn);
        }

        args.set(1, columnNameMap.get(argColumn));
    }

    private static void validateTimeFormat(List<String> args, Map<String, String> columnNameMap) throws
            AnalysisException {
        if (args.size() != 3) {
            throw new AnalysisException("Function time_format needs 3 args");
        }

        String outputFormat = args.get(0);
        String inputFormat = args.get(1);
        String regex = "^(%[YMmdHhiSs][ -:]?){0,5}%[YMmdHhiSs]$";
        if (!outputFormat.matches(regex)) {
            throw new AnalysisException("Date format error. regex: " + regex + ", arg: " + outputFormat);
        }
        if (!inputFormat.matches(regex)) {
            throw new AnalysisException("Date format error. regex: " + regex + ", arg: " + inputFormat);
        }

        String argColumn = args.get(2);
        if (!columnNameMap.containsKey(argColumn)) {
            throw new AnalysisException("Column is not in sources, column: " + argColumn);
        }

        args.set(2, columnNameMap.get(argColumn));
    }

    private static void validateDefaultValue(List<String> args, Column column) throws AnalysisException {
        if (args.size() != 1) {
            throw new AnalysisException("Function default_value needs 1 arg");
        }

        if (!column.isAllowNull() && args.get(0) == null) {
            throw new AnalysisException("Column is not null, column: " + column.getName());
        }

        if (args.get(0) != null) {
            ColumnDef.validateDefaultValue(column.getOriginType(), args.get(0), column.getDefaultValueExprDef());
        }
    }

    private static void validateMd5sum(List<String> args, Map<String, String> columnNameMap) throws AnalysisException {
        for (int i = 0; i < args.size(); ++i) {
            String argColumn = args.get(i);
            if (!columnNameMap.containsKey(argColumn)) {
                throw new AnalysisException("Column is not in sources, column: " + argColumn);
            }

            args.set(i, columnNameMap.get(argColumn));
        }
    }

    private static void validateReplaceValue(List<String> args, Column column) throws AnalysisException {
        String replaceValue = null;
        if (args.size() == 1) {
            replaceValue = column.getDefaultValue();
            if (replaceValue == null) {
                throw new AnalysisException("Column " + column.getName() + " has no default value");
            }

            args.add(replaceValue);
        } else if (args.size() == 2) {
            replaceValue = args.get(1);
        } else {
            throw new AnalysisException("Function replace_value need 1 or 2 args");
        }

        if (!column.isAllowNull() && replaceValue == null) {
            throw new AnalysisException("Column is not null, column: " + column.getName());
        }

        if (replaceValue != null) {
            ColumnDef.validateDefaultValue(column.getOriginType(), replaceValue, column.getDefaultValueExprDef());
        }
    }

    private static void validateHllHash(List<String> args, Map<String, String> columnNameMap) throws AnalysisException {
        for (int i = 0; i < args.size(); ++i) {
            String argColumn = args.get(i);
            if (!columnNameMap.containsKey(argColumn)) {
                throw new AnalysisException("Column is not in sources, column: " + argColumn);
            }
            args.set(i, columnNameMap.get(argColumn));
        }
    }

    private static void validateNowFunction(Column mappingColumn) throws AnalysisException {
        if (!mappingColumn.getOriginType().isDateType()) {
            throw new AnalysisException("Now() function is only support for DATE/DATETIME column");
        }
    }

    public String getTableName() {
        return tableName;
    }

    public PartitionNames getPartitionNames() {
        return partitionNames;
    }

    public Expr getPrecdingFilterExpr() {
        return precedingFilterExpr;
    }

    public Expr getWhereExpr() {
        return whereExpr;
    }

    public LoadTask.MergeType getMergeType() {
        if (mergeType == null) {
            return LoadTask.MergeType.APPEND;
        }
        return mergeType;
    }

    public Expr getDeleteCondition() {
        return deleteCondition;
    }

    public List<String> getFilePaths() {
        return filePaths;
    }

    public List<String> getFileFieldNames() {
        if (fileFieldNames == null || fileFieldNames.isEmpty()) {
            return null;
        }
        return fileFieldNames;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public List<String> getColumnsFromPath() {
        return columnsFromPath;
    }

    public String getColumnSeparator() {
        if (columnSeparator == null) {
            return null;
        }
        return columnSeparator.getSeparator();
    }

    public boolean isNegative() {
        return isNegative;
    }

    public TNetworkAddress getBeAddr() {
        return beAddr;
    }

    public void setBeAddr(TNetworkAddress addr) {
        beAddr = addr;
    }

    public String getLineDelimiter() {
        if (lineDelimiter == null) {
            return null;
        }
        return lineDelimiter.getSeparator();
    }

    public void setLineDelimiter(Separator lineDelimiter) {
        this.lineDelimiter = lineDelimiter;
    }

    public String getSequenceCol() {
        return sequenceCol;
    }

    public void setColumnDef(String columnDef) {
        this.columnDef = columnDef;
    }

    public boolean hasSequenceCol() {
        return !Strings.isNullOrEmpty(sequenceCol);
    }

    public List<Long> getFileSize() {
        return fileSize;
    }

    public void setFileSize(List<Long> fileSize) {
        this.fileSize = fileSize;
    }

    public long getBackendId() {
        return backendId;
    }

    public void setBackendId(long backendId) {
        this.backendId = backendId;
    }

    public boolean isStripOuterArray() {
        return stripOuterArray;
    }

    public void setStripOuterArray(boolean stripOuterArray) {
        this.stripOuterArray = stripOuterArray;
    }

    public boolean isFuzzyParse() {
        return fuzzyParse;
    }

    public void setFuzzyParse(boolean fuzzyParse) {
        this.fuzzyParse = fuzzyParse;
    }

    public boolean isNumAsString() {
        return numAsString;
    }

    public void setNumAsString(boolean numAsString) {
        this.numAsString = numAsString;
    }

    public String getJsonPaths() {
        return jsonPaths;
    }

    public void setJsonPaths(String jsonPaths) {
        this.jsonPaths = jsonPaths;
    }

    public String getJsonRoot() {
        return jsonRoot;
    }

    public void setJsonRoot(String jsonRoot) {
        this.jsonRoot = jsonRoot;
    }

    @Deprecated
    public void addColumnMapping(String functionName, Pair<String, List<String>> pair) {
        if (Strings.isNullOrEmpty(functionName) || pair == null) {
            return;
        }
        columnToHadoopFunction.put(functionName, pair);
    }

    public Map<String, Pair<String, List<String>>> getColumnToHadoopFunction() {
        return columnToHadoopFunction;
    }

    public List<ImportColumnDesc> getParsedColumnExprList() {
        return parsedColumnExprList;
    }

    public void setIsHadoopLoad(boolean isHadoopLoad) {
        this.isHadoopLoad = isHadoopLoad;
    }

    public boolean isHadoopLoad() {
        return isHadoopLoad;
    }

    public String getSrcTableName() {
        return srcTableName;
    }

    public boolean isLoadFromTable() {
        return !Strings.isNullOrEmpty(srcTableName);
    }

    /*
     * Analyze parsedExprMap and columnToHadoopFunction from columns, columns from path and columnMappingList
     * Example:
     *      columns (col1, tmp_col2, tmp_col3)
     *      columns from path as (col4, col5)
     *      set (col2=tmp_col2+1, col3=strftime("%Y-%m-%d %H:%M:%S", tmp_col3))
     *
     * Result:
     *      parsedExprMap = {"col1": null, "tmp_col2": null, "tmp_col3": null, "col4": null, "col5": null,
     *                       "col2": "tmp_col2+1", "col3": "strftime("%Y-%m-%d %H:%M:%S", tmp_col3)"}
     *      columnToHadoopFunction = {"col3": "strftime("%Y-%m-%d %H:%M:%S", tmp_col3)"}
     */
    private void analyzeColumns() throws AnalysisException {
        if ((fileFieldNames == null || fileFieldNames.isEmpty())
                && (columnsFromPath != null && !columnsFromPath.isEmpty())) {
            throw new AnalysisException("Can not specify columns_from_path without column_list");
        }

        // used to check duplicated column name in COLUMNS and COLUMNS FROM PATH
        Set<String> columnNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        // merge columns exprs from columns, columns from path and columnMappingList
        // 1. analyze columns
        if (fileFieldNames != null && !fileFieldNames.isEmpty()) {
            for (String columnName : fileFieldNames) {
                if (!columnNames.add(columnName)) {
                    throw new AnalysisException("Duplicate column: " + columnName);
                }
                ImportColumnDesc importColumnDesc = new ImportColumnDesc(columnName, null);
                parsedColumnExprList.add(importColumnDesc);
            }
        }

        // 2. analyze columns from path
        if (columnsFromPath != null && !columnsFromPath.isEmpty()) {
            if (isHadoopLoad) {
                throw new AnalysisException("Hadoop load does not support specifying columns from path");
            }
            for (String columnName : columnsFromPath) {
                if (!columnNames.add(columnName)) {
                    throw new AnalysisException("Duplicate column: " + columnName);
                }
                ImportColumnDesc importColumnDesc = new ImportColumnDesc(columnName, null);
                parsedColumnExprList.add(importColumnDesc);
            }
        }

        // 3: analyze column mapping
        if (columnMappingList == null || columnMappingList.isEmpty()) {
            return;
        }

        // used to check duplicated column name in SET clause
        Set<String> columnMappingNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Step2: analyze column mapping
        // the column expr only support the SlotRef or eq binary predicate which's child(0) must be a SloRef.
        // the duplicate column name of SloRef is forbidden.
        for (Expr columnExpr : columnMappingList) {
            if (!(columnExpr instanceof BinaryPredicate)) {
                throw new AnalysisException("Mapping function expr only support the column or eq binary predicate. "
                        + "Expr: " + columnExpr.toSql());
            }
            BinaryPredicate predicate = (BinaryPredicate) columnExpr;
            if (predicate.getOp() != Operator.EQ) {
                throw new AnalysisException("Mapping function expr only support the column or eq binary predicate. "
                        + "The mapping operator error, op: " + predicate.getOp());
            }
            Expr child0 = predicate.getChild(0);
            if (!(child0 instanceof SlotRef)) {
                throw new AnalysisException("Mapping function expr only support the column or eq binary predicate. "
                        + "The mapping column error. column: " + child0.toSql());
            }
            String column = ((SlotRef) child0).getColumnName();
            if (!columnMappingNames.add(column)) {
                throw new AnalysisException("Duplicate column mapping: " + column);
            }
            // hadoop load only supports the FunctionCallExpr
            Expr child1 = predicate.getChild(1);
            if (isHadoopLoad && !(child1 instanceof FunctionCallExpr)) {
                throw new AnalysisException("Hadoop load only supports the designated function. "
                        + "The error mapping function is:" + child1.toSql());
            }
            ImportColumnDesc importColumnDesc = new ImportColumnDesc(column, child1);
            parsedColumnExprList.add(importColumnDesc);
            if (child1 instanceof FunctionCallExpr) {
                analyzeColumnToHadoopFunction(column, child1);
            }
        }
    }

    private void analyzeMultiLoadColumns() throws AnalysisException {
        if (columnDef == null || columnDef.isEmpty()) {
            return;
        }
        String columnsSQL = "COLUMNS (" + columnDef + ")";
        SqlParser parser = new SqlParser(new SqlScanner(new StringReader(columnsSQL)));
        ImportColumnsStmt columnsStmt;
        try {
            columnsStmt = (ImportColumnsStmt) SqlParserUtils.getFirstStmt(parser);
        } catch (Error e) {
            LOG.warn("error happens when parsing columns, sql={}", columnsSQL, e);
            throw new AnalysisException("failed to parsing columns' header, maybe contain unsupported character");
        } catch (AnalysisException e) {
            LOG.warn("analyze columns' statement failed, sql={}, error={}",
                    columnsSQL, parser.getErrorMsg(columnsSQL), e);
            String errorMessage = parser.getErrorMsg(columnsSQL);
            if (errorMessage == null) {
                throw e;
            } else {
                throw new AnalysisException(errorMessage, e);
            }
        } catch (Exception e) {
            LOG.warn("failed to parse columns header, sql={}", columnsSQL, e);
            throw new AnalysisException("parse columns header failed", e);
        }

        if (columnsStmt.getColumns() != null && !columnsStmt.getColumns().isEmpty()) {
            parsedColumnExprList = columnsStmt.getColumns();
        }
    }

    private void analyzeColumnToHadoopFunction(String columnName, Expr child1) throws AnalysisException {
        Preconditions.checkState(child1 instanceof FunctionCallExpr);
        FunctionCallExpr functionCallExpr = (FunctionCallExpr) child1;
        String functionName = functionCallExpr.getFnName().getFunction();
        if (!HADOOP_SUPPORT_FUNCTION_NAMES.contains(functionName.toLowerCase())) {
            return;
        }
        List<Expr> paramExprs = functionCallExpr.getParams().exprs();
        List<String> args = Lists.newArrayList();

        for (Expr paramExpr : paramExprs) {
            if (paramExpr instanceof SlotRef) {
                SlotRef slot = (SlotRef) paramExpr;
                args.add(slot.getColumnName());
            } else if (paramExpr instanceof StringLiteral) {
                StringLiteral literal = (StringLiteral) paramExpr;
                args.add(literal.getValue());
            } else if (paramExpr instanceof NullLiteral) {
                args.add(null);
            } else {
                if (isHadoopLoad) {
                    // hadoop function only support slot, string and null parameters
                    throw new AnalysisException("Mapping function args error, arg: " + paramExpr.toSql());
                }
            }
        }

        Pair<String, List<String>> functionPair = Pair.of(functionName, args);
        columnToHadoopFunction.put(columnName, functionPair);
    }

    private void analyzeSequenceCol(String fullDbName) throws AnalysisException {
        Database db = Env.getCurrentInternalCatalog().getDbOrAnalysisException(fullDbName);
        OlapTable olapTable = db.getOlapTableOrAnalysisException(tableName);
        // no sequence column in load and table schema
        if (!hasSequenceCol() && !olapTable.hasSequenceCol()) {
            return;
        }
        // check olapTable schema and sequenceCol
        if (olapTable.hasSequenceCol() && !hasSequenceCol()) {
            throw new AnalysisException("Table " + olapTable.getName()
                    + " has sequence column, need to specify the sequence column");
        }
        if (hasSequenceCol() && !olapTable.hasSequenceCol()) {
            throw new AnalysisException("There is no sequence column in the table " + olapTable.getName());
        }
        // check source sequence column is in parsedColumnExprList or Table base schema
        boolean hasSourceSequenceCol = false;
        if (!parsedColumnExprList.isEmpty()) {
            for (ImportColumnDesc importColumnDesc : parsedColumnExprList) {
                if (importColumnDesc.getColumnName().equals(sequenceCol)) {
                    hasSourceSequenceCol = true;
                    break;
                }
            }
        } else {
            List<Column> columns = olapTable.getBaseSchema();
            for (Column column : columns) {
                if (column.getName().equals(sequenceCol)) {
                    hasSourceSequenceCol = true;
                    break;
                }
            }
        }
        if (!hasSourceSequenceCol) {
            throw new AnalysisException("There is no sequence column " + sequenceCol + " in the " + olapTable.getName()
                    + " or the COLUMNS and SET clause");
        }
    }

    private void analyzeProperties() throws AnalysisException {
        Map<String, String> analysisMap = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
        analysisMap.putAll(properties);

        if (analysisMap.containsKey(LoadStmt.KEY_IN_PARAM_LINE_DELIMITER)) {
            lineDelimiter = new Separator(analysisMap.get(LoadStmt.KEY_IN_PARAM_LINE_DELIMITER));
            lineDelimiter.analyze();
        }

        if (analysisMap.containsKey(LoadStmt.KEY_IN_PARAM_FUZZY_PARSE)) {
            fuzzyParse = Boolean.parseBoolean(analysisMap.get(LoadStmt.KEY_IN_PARAM_FUZZY_PARSE));
        }

        if (analysisMap.containsKey(LoadStmt.KEY_IN_PARAM_STRIP_OUTER_ARRAY)) {
            stripOuterArray = Boolean.parseBoolean(analysisMap.get(LoadStmt.KEY_IN_PARAM_STRIP_OUTER_ARRAY));
        }

        if (analysisMap.containsKey(LoadStmt.KEY_IN_PARAM_JSONPATHS)) {
            jsonPaths = analysisMap.get(LoadStmt.KEY_IN_PARAM_JSONPATHS);
        }

        if (analysisMap.containsKey(LoadStmt.KEY_IN_PARAM_JSONROOT)) {
            jsonRoot = analysisMap.get(LoadStmt.KEY_IN_PARAM_JSONROOT);
        }

        if (analysisMap.containsKey(LoadStmt.KEY_IN_PARAM_NUM_AS_STRING)) {
            numAsString = Boolean.parseBoolean(analysisMap.get(LoadStmt.KEY_IN_PARAM_NUM_AS_STRING));
        }
    }

    private void checkLoadPriv(String fullDbName) throws AnalysisException {
        if (Strings.isNullOrEmpty(tableName)) {
            throw new AnalysisException("No table name in load statement.");
        }

        // check auth
        if (!Env.getCurrentEnv().getAuth().checkTblPriv(ConnectContext.get(), fullDbName, tableName,
                PrivPredicate.LOAD)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_TABLEACCESS_DENIED_ERROR, "LOAD",
                    ConnectContext.get().getQualifiedUser(),
                    ConnectContext.get().getRemoteIP(), fullDbName + ": " + tableName);
        }

        // check hive table auth
        if (isLoadFromTable()) {
            if (!Env.getCurrentEnv().getAuth().checkTblPriv(ConnectContext.get(), fullDbName, srcTableName,
                    PrivPredicate.SELECT)) {
                ErrorReport.reportAnalysisException(ErrorCode.ERR_TABLEACCESS_DENIED_ERROR, "SELECT",
                        ConnectContext.get().getQualifiedUser(),
                        ConnectContext.get().getRemoteIP(), fullDbName + ": " + srcTableName);
            }
        }
    }

    public void analyze(String fullDbName) throws AnalysisException {
        if (mergeType != LoadTask.MergeType.MERGE && deleteCondition != null) {
            throw new AnalysisException("not support DELETE ON clause when merge type is not MERGE.");
        }
        if (mergeType == LoadTask.MergeType.MERGE && deleteCondition == null) {
            throw new AnalysisException("Excepted DELETE ON clause when merge type is MERGE.");
        }
        if (mergeType != LoadTask.MergeType.APPEND && isNegative) {
            throw new AnalysisException("not support MERGE or DELETE with NEGATIVE.");
        }
        checkLoadPriv(fullDbName);
        analyzeWithoutCheckPriv(fullDbName);
        if (isNegative && mergeType != LoadTask.MergeType.APPEND) {
            throw new AnalysisException("Negative is only used when merge type is append.");
        }
    }

    public void analyzeWithoutCheckPriv(String fullDbName) throws AnalysisException {
        if (!isLoadFromTable()) {
            if (filePaths == null || filePaths.isEmpty()) {
                throw new AnalysisException("No file path in load statement.");
            }
            for (int i = 0; i < filePaths.size(); ++i) {
                filePaths.set(i, filePaths.get(i).trim());
            }
        }

        if (columnSeparator != null) {
            columnSeparator.analyze();
        }

        if (partitionNames != null) {
            partitionNames.analyze(null);
        }

        analyzeColumns();
        analyzeMultiLoadColumns();
        analyzeSequenceCol(fullDbName);

        if (properties != null) {
            analyzeProperties();
        }
    }

    /*
     * If user does not specify COLUMNS in load stmt, we fill it here.
     * eg1:
     *      both COLUMNS and SET clause is empty. after fill:
     *      (k1,k2,k3)
     *
     * eg2:
     *      COLUMNS is empty, SET is not empty
     *      SET ( k2 = default_value("2") )
     *      after fill:
     *      (k1, k2, k3)
     *      SET ( k2 = default_value("2") )
     *
     * eg3:
     *      COLUMNS is empty, SET is not empty
     *      SET (k2 = strftime("%Y-%m-%d %H:%M:%S", k2)
     *      after fill:
     *      (k1,k2,k3)
     *      SET (k2 = strftime("%Y-%m-%d %H:%M:%S", k2)
     *
     */
    public void fillColumnInfoIfNotSpecified(List<Column> baseSchema) {
        if (fileFieldNames != null && !fileFieldNames.isEmpty()) {
            return;
        }

        fileFieldNames = Lists.newArrayList();

        Set<String> mappingColNames = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        for (ImportColumnDesc importColumnDesc : parsedColumnExprList) {
            mappingColNames.add(importColumnDesc.getColumnName());
        }

        for (Column column : baseSchema) {
            if (!mappingColNames.contains(column.getName())) {
                parsedColumnExprList.add(new ImportColumnDesc(column.getName(), null));
            }
            fileFieldNames.add(column.getName());
        }

        LOG.debug("after fill column info. columns: {}, parsed column exprs: {}", fileFieldNames, parsedColumnExprList);
    }

    public String toSql() {
        StringBuilder sb = new StringBuilder();
        if (isLoadFromTable()) {
            sb.append(mergeType.toString());
            sb.append(" DATA FROM TABLE ").append(srcTableName);
        } else {
            sb.append(mergeType.toString());
            sb.append(" DATA INFILE (");
            Joiner.on(", ").appendTo(sb, Lists.transform(filePaths, new Function<String, String>() {
                @Override
                public String apply(String s) {
                    return "'" + s + "'";
                }
            })).append(")");
        }
        if (isNegative) {
            sb.append(" NEGATIVE");
        }
        sb.append(" INTO TABLE ").append(tableName);
        if (partitionNames != null) {
            sb.append(" ");
            sb.append(partitionNames.toSql());
        }
        if (columnSeparator != null) {
            sb.append(" COLUMNS TERMINATED BY ").append(columnSeparator.toSql());
        }
        if (fileFormat != null && !fileFormat.isEmpty()) {
            sb.append(" FORMAT AS '" + fileFormat + "'");
        }
        if (fileFieldNames != null && !fileFieldNames.isEmpty()) {
            sb.append(" (");
            Joiner.on(", ").appendTo(sb, fileFieldNames).append(")");
        }
        if (columnsFromPath != null && !columnsFromPath.isEmpty()) {
            sb.append(" COLUMNS FROM PATH AS (");
            Joiner.on(", ").appendTo(sb, columnsFromPath).append(")");
        }
        if (columnMappingList != null && !columnMappingList.isEmpty()) {
            sb.append(" SET (");
            Joiner.on(", ").appendTo(sb, Lists.transform(columnMappingList, new Function<Expr, Object>() {
                @Override
                public Object apply(Expr expr) {
                    return expr.toSql();
                }
            })).append(")");
        }
        if (whereExpr != null) {
            sb.append(" WHERE ").append(whereExpr.toSql());
        }
        if (deleteCondition != null && mergeType == LoadTask.MergeType.MERGE) {
            sb.append(" DELETE ON ").append(deleteCondition.toSql());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toSql();
    }
}
