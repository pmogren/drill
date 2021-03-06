/**
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
 */
package org.apache.drill.exec.ops;

import java.io.IOException;
import java.util.Collection;

import io.netty.buffer.DrillBuf;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.jdbc.SimpleCalciteSchema;

import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.exec.ExecConstants;
import org.apache.drill.exec.expr.fn.FunctionImplementationRegistry;
import org.apache.drill.common.exceptions.DrillRuntimeException;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.memory.OutOfMemoryException;
import org.apache.drill.exec.planner.physical.PlannerSettings;
import org.apache.drill.exec.planner.sql.DrillOperatorTable;
import org.apache.drill.exec.proto.BitControl.QueryContextInformation;
import org.apache.drill.exec.proto.CoordinationProtos.DrillbitEndpoint;
import org.apache.drill.exec.rpc.user.UserSession;
import org.apache.drill.exec.server.DrillbitContext;
import org.apache.drill.exec.server.options.OptionManager;
import org.apache.drill.exec.server.options.QueryOptionManager;
import org.apache.drill.exec.store.PartitionExplorer;
import org.apache.drill.exec.store.PartitionExplorerImpl;
import org.apache.drill.exec.store.SchemaConfig;
import org.apache.drill.exec.store.StoragePluginRegistry;
import org.apache.drill.exec.testing.ExecutionControls;
import org.apache.drill.exec.util.ImpersonationUtil;
import org.apache.drill.exec.util.Utilities;

// TODO except for a couple of tests, this is only created by Foreman
// TODO the many methods that just return drillbitContext.getXxx() should be replaced with getDrillbitContext()
// TODO - consider re-name to PlanningContext, as the query execution context actually appears
// in fragment contexts
public class QueryContext implements AutoCloseable, UdfUtilities {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(QueryContext.class);

  private static final int INITIAL_OFF_HEAP_ALLOCATION_IN_BYTES = 1024 * 1024;
  private static final int MAX_OFF_HEAP_ALLOCATION_IN_BYTES = 256 * 1024 * 1024;

  private final DrillbitContext drillbitContext;
  private final UserSession session;
  private final OptionManager queryOptions;
  private final PlannerSettings plannerSettings;
  private final DrillOperatorTable table;
  private final ExecutionControls executionControls;

  private final BufferAllocator allocator;
  private final BufferManager bufferManager;
  private final ContextInformation contextInformation;
  private final QueryContextInformation queryContextInfo;
  private final ViewExpansionContext viewExpansionContext;

  /*
   * Flag to indicate if close has been called, after calling close the first
   * time this is set to true and the close method becomes a no-op.
   */
  private boolean closed = false;

  public QueryContext(final UserSession session, final DrillbitContext drillbitContext) {
    this.drillbitContext = drillbitContext;
    this.session = session;
    queryOptions = new QueryOptionManager(session.getOptions());
    executionControls = new ExecutionControls(queryOptions, drillbitContext.getEndpoint());
    plannerSettings = new PlannerSettings(queryOptions, getFunctionRegistry());
    plannerSettings.setNumEndPoints(drillbitContext.getBits().size());
    table = new DrillOperatorTable(getFunctionRegistry());

    queryContextInfo = Utilities.createQueryContextInfo(session.getDefaultSchemaName());
    contextInformation = new ContextInformation(session.getCredentials(), queryContextInfo);

    try {
      allocator = drillbitContext.getAllocator().getChildAllocator(null, INITIAL_OFF_HEAP_ALLOCATION_IN_BYTES,
          MAX_OFF_HEAP_ALLOCATION_IN_BYTES, false);
    } catch (OutOfMemoryException e) {
      throw new DrillRuntimeException("Error creating off-heap allocator for planning context.",e);
    }
    // TODO(DRILL-1942) the new allocator has this capability built-in, so this can be removed once that is available
    bufferManager = new BufferManager(this.allocator, null);
    viewExpansionContext = new ViewExpansionContext(this);
  }

  public PlannerSettings getPlannerSettings() {
    return plannerSettings;
  }

  public UserSession getSession() {
    return session;
  }

  public BufferAllocator getAllocator() {
    return allocator;
  }

  /**
   * Return reference to default schema instance in a schema tree. Each {@link org.apache.calcite.schema.SchemaPlus}
   * instance can refer to its parent and its children. From the returned reference to default schema instance,
   * clients can traverse the entire schema tree and know the default schema where to look up the tables first.
   *
   * @return Reference to default schema instance in a schema tree.
   */
  public SchemaPlus getNewDefaultSchema() {
    final SchemaPlus rootSchema = getRootSchema();
    final SchemaPlus defaultSchema = session.getDefaultSchema(rootSchema);
    if (defaultSchema == null) {
      return rootSchema;
    }

    return defaultSchema;
  }

  /**
   * Get root schema with schema owner as the user who issued the query that is managed by this QueryContext.
   * @return Root of the schema tree.
   */
  public SchemaPlus getRootSchema() {
    return getRootSchema(getQueryUserName());
  }

  /**
   * Return root schema with schema owner as the given user.
   *
   * @param userName User who owns the schema tree.
   * @return Root of the schema tree.
   */
  public SchemaPlus getRootSchema(String userName) {
    final String schemaUser = isImpersonationEnabled() ? userName : ImpersonationUtil.getProcessUserName();
    final SchemaConfig schemaConfig = SchemaConfig.newBuilder(schemaUser, this).build();
    return getRootSchema(schemaConfig);
  }

  /**
   *  Create and return a SchemaTree with given <i>schemaConfig</i>.
   * @param schemaConfig
   * @return
   */
  public SchemaPlus getRootSchema(SchemaConfig schemaConfig) {
    try {
      final SchemaPlus rootSchema = SimpleCalciteSchema.createRootSchema(false);
      drillbitContext.getSchemaFactory().registerSchemas(schemaConfig, rootSchema);
      return rootSchema;
    } catch(IOException e) {
      // We can't proceed further without a schema, throw a runtime exception.
      final String errMsg = String.format("Failed to create schema tree: %s", e.getMessage());
      logger.error(errMsg, e);
      throw new DrillRuntimeException(errMsg, e);
    }
  }

  /**
   * Get the user name of the user who issued the query that is managed by this QueryContext.
   * @return
   */
  public String getQueryUserName() {
    return session.getCredentials().getUserName();
  }

  public OptionManager getOptions() {
    return queryOptions;
  }

  public ExecutionControls getExecutionControls() {
    return executionControls;
  }

  public DrillbitEndpoint getCurrentEndpoint() {
    return drillbitContext.getEndpoint();
  }

  public StoragePluginRegistry getStorage() {
    return drillbitContext.getStorage();
  }

  public Collection<DrillbitEndpoint> getActiveEndpoints() {
    return drillbitContext.getBits();
  }

  public DrillConfig getConfig() {
    return drillbitContext.getConfig();
  }

  public FunctionImplementationRegistry getFunctionRegistry() {
    return drillbitContext.getFunctionImplementationRegistry();
  }

  public ViewExpansionContext getViewExpansionContext() {
    return viewExpansionContext;
  }

  public boolean isImpersonationEnabled() {
     return getConfig().getBoolean(ExecConstants.IMPERSONATION_ENABLED);
  }

  public DrillOperatorTable getDrillOperatorTable() {
    return table;
  }

  public QueryContextInformation getQueryContextInfo() {
    return queryContextInfo;
  }

  @Override
  public ContextInformation getContextInformation() {
    return contextInformation;
  }

  @Override
  public DrillBuf getManagedBuffer() {
    return bufferManager.getManagedBuffer();
  }

  @Override
  public PartitionExplorer getPartitionExplorer() {
    return new PartitionExplorerImpl(getRootSchema());
  }

  @Override
  public void close() throws Exception {
    try {
      if (!closed) {
        // TODO(DRILL-1942) the new allocator has this capability built-in, so this can be removed once that is available
        bufferManager.close();
        allocator.close();
      }
    } finally {
      closed = true;
    }
  }
}
