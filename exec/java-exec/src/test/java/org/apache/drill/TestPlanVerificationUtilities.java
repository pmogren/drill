/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.drill;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestPlanVerificationUtilities extends PlanTestBase {

  @Test
  public void testPlanVerifier() throws Exception {
    String query = "select * from cp.`tpch/lineitem.parquet`";
    String expectedPattern = "selectionRoot=/tpch/lineitem.parquet, numFiles=[1-9]+, ";
    String excludedPattern = "part.parquet";
    testPlanMatchingPatterns(query,
        new String[] {expectedPattern}, new String[] {excludedPattern});

    testPlanMatchingPatterns(query,
        null, new String[] {excludedPattern});

    testPlanMatchingPatterns(query,
        new String[] {expectedPattern}, new String[] {});

    try {
      testPlanMatchingPatterns(query,
          new String[] {expectedPattern}, new String[] {expectedPattern});
    } catch (AssertionError ex) {
      assertEquals(ex.getMessage(), UNEXPECTED_FOUND + expectedPattern);
    }

    try {
      testPlanMatchingPatterns(query,
          new String[] {excludedPattern}, new String[] {excludedPattern});
    } catch (AssertionError ex) {
      assertEquals(ex.getMessage(), EXPECTED_NOT_FOUND + excludedPattern);
    }
  }
}
