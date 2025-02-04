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

package org.apache.doris.udf;

import org.apache.hadoop.hive.ql.exec.UDF;

import org.apache.hadoop.hive.ql.exec.UDF;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class DateWindowRollup extends UDF {

    /**
     * @param startTime yyyy-MM-dd
     * @param start    从startTime 前-start天
     * @param end      至startTime 前-end天
     * @return ${yyyy-MM-dd}+end ，${yyyy-MM-dd}+(end+1),...,${yyyy-MM-dd}+(start)
     */
    public String evaluate(LocalDate startTime, Integer start, Integer end)  {
        if (startTime == null || start == null || end == null){
            return null;
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<String> ret = new ArrayList<>();
        for (int i = start; i <= end - 1; i++) {
            LocalDate groupDate = startTime.plusDays(i);
            if (groupDate.isAfter(yesterday)) {
                break;
            }
            String dateFormat = formatter.format(groupDate);
            ret.add(dateFormat);
        }
        return String.join(",", ret);
    }

}