/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.alerts.engine.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.Singleton;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.hawkular.alerts.api.model.Severity;
import org.hawkular.alerts.api.model.condition.AvailabilityCondition;
import org.hawkular.alerts.api.model.condition.CompareCondition;
import org.hawkular.alerts.api.model.condition.Condition;
import org.hawkular.alerts.api.model.condition.StringCondition;
import org.hawkular.alerts.api.model.condition.ThresholdCondition;
import org.hawkular.alerts.api.model.condition.ThresholdRangeCondition;
import org.hawkular.alerts.api.model.dampening.Dampening;
import org.hawkular.alerts.api.model.trigger.Tag;
import org.hawkular.alerts.api.model.trigger.Trigger;
import org.hawkular.alerts.api.model.trigger.TriggerTemplate;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.alerts.api.services.DefinitionsEvent;
import org.hawkular.alerts.api.services.DefinitionsListener;
import org.hawkular.alerts.api.services.DefinitionsService;
import org.hawkular.alerts.engine.log.MsgLogger;
import org.jboss.logging.Logger;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.util.concurrent.Futures;

/**
 * A Cassandra implementation of {@link org.hawkular.alerts.api.services.DefinitionsService}.
 *
 * @author Lucas Ponce
 */
@Singleton
public class CassDefinitionsServiceImpl implements DefinitionsService {
    private static final String JBOSS_DATA_DIR = "jboss.server.data.dir";
    private static final String INIT_FOLDER = "hawkular-alerts";
    private static final String CASSANDRA_KEYSPACE = "hawkular-alerts.cassandra-keyspace";
    private static final String ALERTS_SERVICE = "hawkular-alerts.alerts-service-jndi";
    private final MsgLogger msgLog = MsgLogger.LOGGER;
    private final Logger log = Logger.getLogger(CassDefinitionsServiceImpl.class);
    private AlertsService alertsService;
    private Session session;
    private String keyspace;
    private boolean initialized = false;

    private List<DefinitionsListener> listeners = new ArrayList<>();

    private PreparedStatement insertTrigger;
    private PreparedStatement insertTriggerActions;
    private PreparedStatement selectTriggerConditions;
    private PreparedStatement selectTriggerConditionsTriggerMode;
    private PreparedStatement deleteConditionsMode;
    private PreparedStatement insertAvailabilityCondition;
    private PreparedStatement insertCompareCondition;
    private PreparedStatement insertStringCondition;
    private PreparedStatement insertThresholdCondition;
    private PreparedStatement insertThresholdRangeCondition;
    private PreparedStatement insertTag;
    private PreparedStatement insertDampening;
    private PreparedStatement insertAction;
    private PreparedStatement selectAllTriggers;
    private PreparedStatement selectTriggerActions;
    private PreparedStatement selectTenantTriggers;
    private PreparedStatement selectAllConditions;
    private PreparedStatement selectAllConditionsByTenant;
    private PreparedStatement selectAllDampenings;
    private PreparedStatement selectAllDampeningsByTenant;
    private PreparedStatement selectAllActions;
    private PreparedStatement selectAllActionsByTenant;
    private PreparedStatement selectTrigger;
    private PreparedStatement selectTriggerDampenings;
    private PreparedStatement selectTriggerDampeningsMode;
    private PreparedStatement deleteDampenings;
    private PreparedStatement deleteConditions;
    private PreparedStatement deleteTriggers;
    private PreparedStatement updateTrigger;
    private PreparedStatement deleteTriggerActions;
    private PreparedStatement deleteDampeningId;
    private PreparedStatement selectDampeningId;
    private PreparedStatement updateDampeningId;
    private PreparedStatement selectConditionId;
    private PreparedStatement insertActionPlugin;
    private PreparedStatement deleteActionPlugin;
    private PreparedStatement updateActionPlugin;
    private PreparedStatement selectActionPlugins;
    private PreparedStatement selectActionPlugin;
    private PreparedStatement deleteAction;
    private PreparedStatement updateAction;
    private PreparedStatement selectActionsPlugin;
    private PreparedStatement selectAction;
    private PreparedStatement selectTagsTriggers;
    private PreparedStatement insertTagsTriggers;
    private PreparedStatement updateTagsTriggers;
    private PreparedStatement deleteTagsTriggers;
    private PreparedStatement selectTags;
    private PreparedStatement selectTagsByCategoryAndName;
    private PreparedStatement selectTagsByCategory;
    private PreparedStatement selectTagsByName;
    private PreparedStatement deleteTags;
    private PreparedStatement deleteTagsByName;

    public CassDefinitionsServiceImpl() {
    }

    public AlertsService getAlertsService() {
        return alertsService;
    }

    public void setAlertsService(AlertsService alertsService) {
        this.alertsService = alertsService;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public String getKeyspace() {
        return keyspace;
    }

    public void setKeyspace(String keyspace) {
        this.keyspace = keyspace;
    }

    @PostConstruct
    public void init() {
        try {
            if (this.keyspace == null) {
                this.keyspace = AlertProperties.getProperty(CASSANDRA_KEYSPACE, "hawkular_alerts");
            }

            if (session == null) {
                session = CassCluster.getSession();
            }

            initPreparedStatements();

            initialData();

            if (alertsService == null) {
                try {
                    InitialContext ctx = new InitialContext();
                    alertsService = (AlertsService) ctx
                            .lookup(AlertProperties.getProperty(ALERTS_SERVICE,
                                    "java:app/hawkular-alerts-engine/CassAlertsServiceImpl"));
                } catch (NamingException e) {
                    log.debugf(e.getMessage(), e);
                    msgLog.errorCannotWithAlertsService(e.getMessage());
                }
            }
        } catch (Throwable t) {
            msgLog.errorCannotInitializeDefinitionsService(t.getMessage());
            t.printStackTrace();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (session != null) {
            session.close();
        }
    }

    private void initialData() throws IOException {
        String data = System.getProperty(JBOSS_DATA_DIR);
        if (data == null || data.isEmpty()) {
            msgLog.errorFolderNotFound(data);
            return;
        }
        String folder = data + "/" + INIT_FOLDER;
        initFiles(folder);
        initialized = true;
    }

    private void initPreparedStatements() {
        if (insertTrigger == null) {
            insertTrigger = session.prepare("INSERT INTO " + keyspace + ".triggers " +
                    "(name, description, autoDisable, autoResolve, autoResolveAlerts, severity, firingMatch, " +
                    "autoResolveMatch, id, enabled, tenantId) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ");
        }
        if (insertTriggerActions == null) {
            insertTriggerActions = session.prepare("INSERT INTO " + keyspace + ".triggers_actions " +
                    "(tenantId, triggerId, actionPlugin, actions) VALUES (?, ?, ?, ?) ");
        }
        if (selectTriggerConditions == null) {
            selectTriggerConditions = session.prepare("SELECT triggerId, triggerMode, type, conditionSetSize, " +
                    "conditionSetIndex, conditionId, dataId, operator, data2Id, data2Multiplier, pattern, " +
                    "ignoreCase, threshold, operatorLow, operatorHigh, thresholdLow, thresholdHigh, inRange, tenantId"
                    +
                    " FROM " + keyspace + ".conditions " +
                    "WHERE tenantId = ? AND triggerId = ? ORDER BY triggerId, triggerMode, type");
        }
        if (selectTriggerConditionsTriggerMode == null) {
            selectTriggerConditionsTriggerMode = session.prepare("SELECT triggerId, triggerMode, type, " +
                    "conditionSetSize, conditionSetIndex, conditionId, dataId, operator, data2Id, data2Multiplier, " +
                    "pattern, ignoreCase, threshold, operatorLow, operatorHigh, thresholdLow, thresholdHigh, inRange,"
                    +
                    " tenantId " +
                    "FROM " + keyspace + ".conditions " +
                    "WHERE tenantId = ? AND triggerId = ? AND triggerMode = ? " +
                    "ORDER BY triggerId, triggerMode, type");
        }
        if (deleteConditionsMode == null) {
            deleteConditionsMode = session.prepare("DELETE FROM " + keyspace + ".conditions " +
                    "WHERE tenantId = ? AND triggerId = ? AND triggerMode = ? ");
        }
        if (insertAvailabilityCondition == null) {
            insertAvailabilityCondition = session.prepare("INSERT INTO " + keyspace + ".conditions " +
                    "(tenantId, triggerId, triggerMode, type, conditionSetSize, conditionSetIndex, conditionId, " +
                    "dataId, operator) VALUES (?, ?, ?, 'AVAILABILITY', ?, ?, ?, ?, ?) ");
        }
        if (insertCompareCondition == null) {
            insertCompareCondition = session.prepare("INSERT INTO " + keyspace + ".conditions " +
                    "(tenantId, triggerId, triggerMode, type, conditionSetSize, conditionSetIndex, conditionId, " +
                    "dataId, operator, data2Id, data2Multiplier) VALUES (?, ?, ?, 'COMPARE', ?, ?, ?, ?, ?, ?, ?) ");
        }
        if (insertStringCondition == null) {
            insertStringCondition = session.prepare("INSERT INTO " + keyspace + ".conditions " +
                    "(tenantId, triggerId, triggerMode, type, conditionSetSize, conditionSetIndex, conditionId, " +
                    "dataId, operator, pattern, ignoreCase) VALUES (?, ?, ?, 'STRING', ?, ?, ?, ?, ?, ?, ?) ");
        }
        if (insertThresholdCondition == null) {
            insertThresholdCondition = session.prepare("INSERT INTO " + keyspace + ".conditions " +
                    "(tenantId, triggerId, triggerMode, type, conditionSetSize, conditionSetIndex, conditionId, " +
                    "dataId, operator, threshold) VALUES (?, ?, ?, 'THRESHOLD', ?, ?, ?, ?, ?, ?) ");
        }
        if (insertThresholdRangeCondition == null) {
            insertThresholdRangeCondition = session.prepare("INSERT INTO " + keyspace + ".conditions " +
                    "(tenantId, triggerId, triggerMode, type, conditionSetSize, conditionSetIndex, conditionId, " +
                    "dataId, operatorLow, operatorHigh, thresholdLow, thresholdHigh, inRange) " +
                    "VALUES (?, ?, ?, 'RANGE', ?, ?, ?, ?, ?, ?, ?, ?, ?) ");
        }
        if (insertTag == null) {
            insertTag = session.prepare("INSERT INTO " + keyspace + ".tags " +
                    "(tenantId, triggerId, category, name, visible) VALUES (?, ?, ?, ?, ?) ");
        }
        if (insertDampening == null) {
            insertDampening = session.prepare("INSERT INTO " + keyspace + ".dampenings " +
                    "(triggerId, triggerMode, type, evalTrueSetting, evalTotalSetting, evalTimeSetting, " +
                    "dampeningId, tenantId) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ");
        }
        if (insertAction == null) {
            insertAction = session.prepare("INSERT INTO " + keyspace + ".actions " +
                    "(tenantId, actionPlugin, actionId, properties) VALUES (?, ?, ?, ?) ");
        }
        if (selectAllTriggers == null) {
            selectAllTriggers = session.prepare("SELECT name, description, autoDisable, autoResolve, " +
                    "autoResolveAlerts, severity, firingMatch, autoResolveMatch, id, enabled, tenantId " +
                    "FROM " + keyspace + ".triggers ");
        }
        if (selectTriggerActions == null) {
            selectTriggerActions = session.prepare("SELECT tenantId, triggerId, actionPlugin, actions " +
                    "FROM " + keyspace + ".triggers_actions " +
                    "WHERE tenantId = ? AND triggerId = ? ");
        }

        if (selectTenantTriggers == null) {
            selectTenantTriggers = session.prepare("SELECT name, description, autoDisable, autoResolve, " +
                    "autoResolveAlerts, firingMatch, autoResolveMatch, id, enabled, tenantId " +
                    "FROM " + keyspace + ".triggers WHERE tenantId = ? ");
        }
        if (selectAllConditions == null) {
            selectAllConditions = session.prepare("SELECT triggerId, triggerMode, type, conditionSetSize, " +
                    "conditionSetIndex, conditionId, dataId, operator, data2Id, data2Multiplier, pattern, " +
                    "ignoreCase, threshold, operatorLow, operatorHigh, thresholdLow, thresholdHigh, inRange," +
                    "tenantId FROM " + keyspace + ".conditions ");
        }
        if (selectAllConditionsByTenant == null) {
            selectAllConditionsByTenant = session.prepare("SELECT triggerId, triggerMode, type, conditionSetSize, " +
                    "conditionSetIndex, conditionId, dataId, operator, data2Id, data2Multiplier, pattern, " +
                    "ignoreCase, threshold, operatorLow, operatorHigh, thresholdLow, thresholdHigh, inRange," +
                    "tenantId FROM " + keyspace + ".conditions WHERE tenantId = ? ");
        }
        if (selectAllDampenings == null) {
            selectAllDampenings = session.prepare("SELECT tenantId, triggerId, triggerMode, type, evalTrueSetting, " +
                    "evalTotalSetting, evalTimeSetting, dampeningId FROM " + keyspace + ".dampenings ");
        }
        if (selectAllDampeningsByTenant == null) {
            selectAllDampeningsByTenant = session.prepare("SELECT tenantId, triggerId, triggerMode, type, " +
                    "evalTrueSetting, " +
                    "evalTotalSetting, evalTimeSetting, dampeningId FROM " + keyspace + ".dampenings " +
                    "WHERE tenantId = ? ");
        }
        if (selectAllActions == null) {
            selectAllActions = session.prepare("SELECT tenantId, actionPlugin, actionId " +
                    "FROM " + keyspace + ".actions ");
        }
        if (selectAllActionsByTenant == null) {
            selectAllActionsByTenant = session.prepare("SELECT actionPlugin, actionId " +
                    "FROM " + keyspace + ".actions WHERE tenantId = ? ");
        }
        if (selectTrigger == null) {
            selectTrigger = session.prepare("SELECT name, description, autoDisable, autoResolve, " +
                    "autoResolveAlerts, firingMatch, autoResolveMatch, severity, id, enabled, tenantId " +
                    "FROM " + keyspace + ".triggers WHERE tenantId = ? AND id = ? ");
        }
        if (selectTriggerDampenings == null) {
            selectTriggerDampenings = session.prepare("SELECT tenantId, triggerId, triggerMode, type, " +
                    "evalTrueSetting, evalTotalSetting, evalTimeSetting, dampeningId " +
                    "FROM " + keyspace + ".dampenings " +
                    "WHERE tenantId = ? AND triggerId = ? ");
        }
        if (selectTriggerDampeningsMode == null) {
            selectTriggerDampeningsMode = session.prepare("SELECT tenantId, triggerId, triggerMode, type, " +
                    "evalTrueSetting, evalTotalSetting, evalTimeSetting, dampeningId " +
                    "FROM " + keyspace + ".dampenings " +
                    "WHERE tenantId = ? AND triggerId = ? and triggerMode = ? ");
        }
        if (deleteDampenings == null) {
            deleteDampenings = session.prepare("DELETE FROM " + keyspace + ".dampenings " +
                    "WHERE tenantId = ? AND triggerId = ? ");
        }
        if (deleteConditions == null) {
            deleteConditions = session.prepare("DELETE FROM " + keyspace + ".conditions " +
                    "WHERE tenantId = ? AND triggerId = ? ");
        }
        if (deleteTriggers == null) {
            deleteTriggers = session.prepare("DELETE FROM " + keyspace + ".triggers " +
                    "WHERE tenantId = ? AND id = ? ");
        }
        if (updateTrigger == null) {
            updateTrigger = session.prepare("UPDATE " + keyspace + ".triggers " +
                    "SET name = ?, description = ?, autoDisable = ?, autoResolve = ?, autoResolveAlerts = ?, " +
                    "firingMatch = ?, autoResolveMatch = ?, severity = ?, enabled = ? " +
                    "WHERE tenantId = ? AND id = ? ");
        }
        if (deleteTriggerActions == null) {
            deleteTriggerActions = session.prepare("DELETE FROM " + keyspace + ".triggers_actions " +
                    "WHERE tenantId = ? AND triggerId = ? ");
        }
        if (deleteDampeningId == null) {
            deleteDampeningId = session.prepare("DELETE FROM " + keyspace + ".dampenings " +
                    "WHERE tenantId = ? AND triggerId = ? AND triggerMode = ? AND dampeningId = ? ");
        }
        if (selectDampeningId == null) {
            selectDampeningId = session.prepare("SELECT triggerId, triggerMode, type, evalTrueSetting, " +
                    "evalTotalSetting, evalTimeSetting, dampeningId, tenantId FROM " + keyspace + ".dampenings " +
                    "WHERE tenantId = ? AND dampeningId = ? ");
        }
        if (updateDampeningId == null) {
            updateDampeningId = session.prepare("UPDATE " + keyspace + ".dampenings " +
                    "SET type = ?, evalTrueSetting = ?, evalTotalSetting = ?, evalTimeSetting = ? " +
                    "WHERE tenantId = ? AND triggerId = ? AND triggerMode = ? AND dampeningId = ? ");
        }
        if (selectConditionId == null) {
            selectConditionId = session.prepare("SELECT triggerId, triggerMode, type, conditionSetSize, " +
                    "conditionSetIndex, conditionId, dataId, operator, data2Id, data2Multiplier, pattern, " +
                    "ignoreCase, threshold, operatorLow, operatorHigh, thresholdLow, thresholdHigh, inRange, " +
                    "tenantId " +
                    "FROM " + keyspace + ".conditions WHERE tenantId = ? AND conditionId = ? ");
        }
        if (insertActionPlugin == null) {
            insertActionPlugin = session.prepare("INSERT INTO " + keyspace + ".action_plugins (actionPlugin, " +
                    "properties) VALUES (?, ?) ");
        }
        if (deleteActionPlugin == null) {
            deleteActionPlugin = session
                    .prepare("DELETE FROM " + keyspace + ".action_plugins WHERE actionPlugin = ? ");
        }
        if (updateActionPlugin == null) {
            updateActionPlugin = session.prepare("UPDATE " + keyspace + ".action_plugins " +
                    "SET properties = ? WHERE actionPlugin = ? ");
        }
        if (selectActionPlugins == null) {
            selectActionPlugins = session.prepare("SELECT actionPlugin FROM " + keyspace + ".action_plugins ");
        }
        if (selectActionPlugin == null) {
            selectActionPlugin = session.prepare("SELECT properties FROM " + keyspace + ".action_plugins " +
                    "WHERE actionPlugin = ? ");
        }
        if (deleteAction == null) {
            deleteAction = session.prepare("DELETE FROM " + keyspace + ".actions " +
                    "WHERE tenantId = ? AND actionPlugin = ? AND actionId = ? ");
        }
        if (updateAction == null) {
            updateAction = session.prepare("UPDATE " + keyspace + ".actions " +
                    "SET properties = ? " +
                    "WHERE tenantId = ? AND actionPlugin = ? AND actionId = ? ");
        }
        if (selectActionsPlugin == null) {
            selectActionsPlugin = session.prepare("SELECT actionId FROM " + keyspace + ".actions " +
                    "WHERE tenantId = ? AND actionPlugin = ? ");
        }
        if (selectAction == null) {
            selectAction = session.prepare("SELECT properties FROM " + keyspace + ".actions " +
                    "WHERE tenantId = ? AND actionPlugin = ? AND actionId = ? ");
        }
        if (selectTagsTriggers == null) {
            selectTagsTriggers = session.prepare("SELECT triggers FROM " + keyspace + ".tags_triggers " +
                    "WHERE tenantId = ? AND category = ? AND name = ? ");
        }
        if (insertTagsTriggers == null) {
            insertTagsTriggers = session.prepare("INSERT INTO " + keyspace + ".tags_triggers " +
                    "(tenantId, category, name, triggers) VALUES (?, ?, ?, ?) ");
        }
        if (updateTagsTriggers == null) {
            updateTagsTriggers = session.prepare("UPDATE " + keyspace + ".tags_triggers " +
                    "SET triggers = ? " +
                    "WHERE tenantId = ? AND name = ? ");
        }
        if (deleteTagsTriggers == null) {
            deleteTagsTriggers = session.prepare("DELETE FROM " + keyspace + ".tags_triggers " +
                    "WHERE tenantId = ? AND name = ? ");
        }
        if (selectTags == null) {
            selectTags = session.prepare("SELECT tenantId, triggerId, category, name, visible FROM " + keyspace +
                    ".tags WHERE tenantId = ? AND triggerId = ? ORDER BY triggerId, name ");
        }
        if (selectTagsByCategoryAndName == null) {
            selectTagsByCategoryAndName = session.prepare("SELECT tenantId, triggerId, category, name, visible FROM " +
                    keyspace + ".tags WHERE tenantId = ? AND triggerId = ? AND category = ? AND name = ? ");
        }
        if (selectTagsByCategory == null) {
            selectTagsByCategory = session.prepare("SELECT tenantId, triggerId, category, name, visible FROM " +
                    keyspace + ".tags WHERE tenantId = ? AND triggerId = ? AND category = ? ");
        }
        if (selectTagsByName == null) {
            selectTagsByName = session.prepare("SELECT tenantId, triggerId, category, name, visible FROM " +
                    keyspace + ".tags WHERE tenantId = ? AND triggerId = ? AND name = ? ");
        }
        if (deleteTags == null) {
            deleteTags = session.prepare("DELETE FROM " + keyspace + ".tags WHERE tenantId = ? AND triggerId = ? ");
        }
        if (deleteTagsByName == null) {
            deleteTagsByName = session.prepare("DELETE FROM " + keyspace + ".tags " +
                    "WHERE tenantId = ? AND triggerId = ? AND name = ?");
        }
    }

    private void initFiles(String folder) {
        if (folder == null) {
            msgLog.errorFolderMustBeNotNull();
            return;
        }
        if (session == null) {
            msgLog.errorDatabaseException("Cassandra session is null. Initialization can not work.");
            return;
        }

        File fFolder = new File(folder);
        if (!fFolder.exists()) {
            log.debugf("Data folder doesn't exits. Skipping initialization.");
            return;
        }

        try {
            initTriggers(fFolder);
            initConditions(fFolder);
            initDampenings(fFolder);
            initActions(fFolder);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                e.printStackTrace();
            }
            msgLog.errorDatabaseException("Error initializing files. Msg: " + e);
        }

    }

    private void initTriggers(File fFolder) throws Exception {
        File triggers = new File(fFolder, "triggers.data");
        if (triggers.exists() && triggers.isFile()) {
            List<String> lines = null;
            try {
                lines = Files.readAllLines(Paths.get(triggers.toURI()), Charset.forName("UTF-8"));
            } catch (IOException e) {
                log.debugf(e.toString(), e);
                msgLog.warningReadingFile("triggers.data");
            }
            if (lines != null && !lines.isEmpty()) {
                for (String line : lines) {
                    if (line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split(",");
                    if (fields.length == 12) {
                        String tenantId = fields[0];
                        String triggerId = fields[1];
                        boolean enabled = Boolean.parseBoolean(fields[2]);
                        String name = fields[3];
                        String description = fields[4];
                        boolean autoDisable = Boolean.parseBoolean(fields[5]);
                        boolean autoResolve = Boolean.parseBoolean(fields[6]);
                        boolean autoResolveAlerts = Boolean.parseBoolean(fields[7]);
                        Severity severity = Severity.valueOf(fields[8]);
                        TriggerTemplate.Match firingMatch = TriggerTemplate.Match.valueOf(fields[9]);
                        TriggerTemplate.Match autoResolveMatch = TriggerTemplate.Match.valueOf(fields[10]);
                        String[] notifiers = fields[11].split("\\|");

                        Trigger trigger = new Trigger(triggerId, name);
                        trigger.setEnabled(enabled);
                        trigger.setAutoDisable(autoDisable);
                        trigger.setAutoResolve(autoResolve);
                        trigger.setAutoResolveAlerts(autoResolveAlerts);
                        trigger.setSeverity(severity);
                        trigger.setDescription(description);
                        trigger.setFiringMatch(firingMatch);
                        trigger.setAutoResolveMatch(autoResolveMatch);
                        trigger.setTenantId(tenantId);
                        for (String notifier : notifiers) {
                            String[] actions = notifier.split("#");
                            String actionPlugin = actions[0];
                            String actionId = actions[1];
                            trigger.addAction(actionPlugin, actionId);
                        }

                        addTrigger(tenantId, trigger);
                        log.debugf("Init file - Inserting [%s]", trigger);
                    }
                }
            }
        } else {
            msgLog.warningFileNotFound("triggers.data");
        }
    }

    private void initConditions(File initFolder) throws Exception {
        File conditions = new File(initFolder, "conditions.data");
        if (conditions.exists() && conditions.isFile()) {
            List<String> lines = null;
            try {
                lines = Files.readAllLines(Paths.get(conditions.toURI()), Charset.forName("UTF-8"));
            } catch (IOException e) {
                msgLog.warningReadingFile("conditions.data");
            }
            if (lines != null && !lines.isEmpty()) {
                for (String line : lines) {
                    if (line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split(",");
                    if (fields.length > 5) {
                        String tenantId = fields[0];
                        String triggerId = fields[1];
                        Trigger.Mode triggerMode = Trigger.Mode.valueOf(fields[2]);
                        int conditionSetSize = Integer.parseInt(fields[3]);
                        int conditionSetIndex = Integer.parseInt(fields[4]);
                        String type = fields[5];
                        if (type != null && !type.isEmpty() && type.equals("threshold") && fields.length == 9) {
                            String dataId = fields[6];
                            String operator = fields[7];
                            Double threshold = Double.parseDouble(fields[8]);

                            ThresholdCondition newCondition = new ThresholdCondition();
                            newCondition.setTriggerId(triggerId);
                            newCondition.setTriggerMode(triggerMode);
                            newCondition.setConditionSetSize(conditionSetSize);
                            newCondition.setConditionSetIndex(conditionSetIndex);
                            newCondition.setDataId(dataId);
                            newCondition.setOperator(ThresholdCondition.Operator.valueOf(operator));
                            newCondition.setThreshold(threshold);
                            newCondition.setTenantId(tenantId);

                            initCondition(newCondition);
                            log.debugf("Init file - Inserting [%s]", newCondition);
                        }
                        if (type != null && !type.isEmpty() && type.equals("range") && fields.length == 12) {
                            String dataId = fields[6];
                            String operatorLow = fields[7];
                            String operatorHigh = fields[8];
                            Double thresholdLow = Double.parseDouble(fields[9]);
                            Double thresholdHigh = Double.parseDouble(fields[10]);
                            boolean inRange = Boolean.parseBoolean(fields[11]);

                            ThresholdRangeCondition newCondition = new ThresholdRangeCondition();
                            newCondition.setTriggerId(triggerId);
                            newCondition.setTriggerMode(triggerMode);
                            newCondition.setConditionSetSize(conditionSetSize);
                            newCondition.setConditionSetIndex(conditionSetIndex);
                            newCondition.setDataId(dataId);
                            newCondition.setOperatorLow(ThresholdRangeCondition.Operator.valueOf(operatorLow));
                            newCondition.setOperatorHigh(ThresholdRangeCondition.Operator.valueOf(operatorHigh));
                            newCondition.setThresholdLow(thresholdLow);
                            newCondition.setThresholdHigh(thresholdHigh);
                            newCondition.setInRange(inRange);
                            newCondition.setTenantId(tenantId);

                            initCondition(newCondition);
                            log.debugf("Init file - Inserting [%s]", newCondition);
                        }
                        if (type != null && !type.isEmpty() && type.equals("compare") && fields.length == 10) {
                            String dataId = fields[6];
                            String operator = fields[7];
                            Double data2Multiplier = Double.parseDouble(fields[8]);
                            String data2Id = fields[9];

                            CompareCondition newCondition = new CompareCondition();
                            newCondition.setTriggerId(triggerId);
                            newCondition.setTriggerMode(triggerMode);
                            newCondition.setConditionSetSize(conditionSetSize);
                            newCondition.setConditionSetIndex(conditionSetIndex);
                            newCondition.setDataId(dataId);
                            newCondition.setOperator(CompareCondition.Operator.valueOf(operator));
                            newCondition.setData2Multiplier(data2Multiplier);
                            newCondition.setData2Id(data2Id);
                            newCondition.setTenantId(tenantId);

                            initCondition(newCondition);
                            log.debugf("Init file - Inserting [%s]", newCondition);
                        }
                        if (type != null && !type.isEmpty() && type.equals("string") && fields.length == 10) {
                            String dataId = fields[6];
                            String operator = fields[7];
                            String pattern = fields[8];
                            boolean ignoreCase = Boolean.parseBoolean(fields[9]);

                            StringCondition newCondition = new StringCondition();
                            newCondition.setTriggerId(triggerId);
                            newCondition.setTriggerMode(triggerMode);
                            newCondition.setConditionSetSize(conditionSetSize);
                            newCondition.setConditionSetIndex(conditionSetIndex);
                            newCondition.setDataId(dataId);
                            newCondition.setOperator(StringCondition.Operator.valueOf(operator));
                            newCondition.setPattern(pattern);
                            newCondition.setIgnoreCase(ignoreCase);
                            newCondition.setTenantId(tenantId);

                            initCondition(newCondition);
                            log.debugf("Init file - Inserting [%s]", newCondition);
                        }
                        if (type != null && !type.isEmpty() && type.equals("availability") && fields.length == 8) {
                            String dataId = fields[6];
                            String operator = fields[7];

                            AvailabilityCondition newCondition = new AvailabilityCondition();
                            newCondition.setTriggerId(triggerId);
                            newCondition.setTriggerMode(triggerMode);
                            newCondition.setConditionSetSize(conditionSetSize);
                            newCondition.setConditionSetIndex(conditionSetIndex);
                            newCondition.setDataId(dataId);
                            newCondition.setOperator(AvailabilityCondition.Operator.valueOf(operator));
                            newCondition.setTenantId(tenantId);

                            initCondition(newCondition);
                            log.debugf("Init file - Inserting [%s]", newCondition);
                        }
                    }
                }
            }
        } else {
            msgLog.warningFileNotFound("conditions.data");
        }
    }

    private void initCondition(Condition condition) throws Exception {
        Collection<Condition> conditions = getTriggerConditions(condition.getTenantId(), condition.getTriggerId(),
                condition.getTriggerMode());
        conditions.add(condition);
        setConditions(condition.getTenantId(), condition.getTriggerId(), condition.getTriggerMode(), conditions);
    }

    private void initDampenings(File initFolder) throws Exception {
        File dampening = new File(initFolder, "dampening.data");
        if (dampening.exists() && dampening.isFile()) {
            List<String> lines = null;
            try {
                lines = Files.readAllLines(Paths.get(dampening.toURI()), Charset.forName("UTF-8"));
            } catch (IOException e) {
                msgLog.warningReadingFile("dampening.data");
            }
            if (lines != null && !lines.isEmpty()) {
                for (String line : lines) {
                    if (line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split(",");
                    if (fields.length == 7) {
                        String tenantId = fields[0];
                        String triggerId = fields[1];
                        Trigger.Mode triggerMode = Trigger.Mode.valueOf(fields[2]);
                        String type = fields[3];
                        int evalTrueSetting = new Integer(fields[4]);
                        int evalTotalSetting = new Integer(fields[5]);
                        int evalTimeSetting = new Integer(fields[6]);

                        Dampening newDampening = new Dampening(triggerId, triggerMode, Dampening.Type.valueOf(type),
                                evalTrueSetting, evalTotalSetting, evalTimeSetting);

                        addDampening(tenantId, newDampening);
                        log.debugf("Init file - Inserting [%s]", newDampening);
                    }
                }
            }
        } else {
            msgLog.warningFileNotFound("dampening.data");
        }
    }

    private void initActions(File initFolder) throws Exception {
        File actions = new File(initFolder, "actions.data");
        if (actions.exists() && actions.isFile()) {
            List<String> lines = null;
            try {
                lines = Files.readAllLines(Paths.get(actions.toURI()), Charset.forName("UTF-8"));
            } catch (IOException e) {
                log.error(e.toString(), e);
            }
            if (lines != null && !lines.isEmpty()) {
                for (String line : lines) {
                    if (line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split(",");
                    if (fields.length > 3) {
                        String tenantId = fields[0];
                        String actionPlugin = fields[1];
                        String actionId = fields[2];

                        Map<String, String> newAction = new HashMap<>();
                        newAction.put("tenantId", tenantId);
                        newAction.put("actionPlugin", actionPlugin);
                        newAction.put("actionId", actionId);

                        for (int i = 3; i < fields.length; i++) {
                            String property = fields[i];
                            String[] properties = property.split("=");
                            if (properties.length == 2) {
                                newAction.put(properties[0], properties[1]);
                            }
                        }
                        addAction(tenantId, actionPlugin, actionId, newAction);
                        log.debugf("Init file - Inserting [%s]", newAction);
                    }
                }
            }
        } else {
            msgLog.warningFileNotFound("actions.data");
        }
    }

    @Override
    public void addAction(String tenantId, String actionPlugin, String actionId, Map<String, String> properties)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("ActionPlugin must be not null");
        }
        if (isEmpty(actionId)) {
            throw new IllegalArgumentException("ActionId must be not null");
        }
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("Properties must be not null");
        }
        properties.put("actionId", actionId);
        properties.put("actionPlugin", actionPlugin);
        properties.put("tenantId", tenantId);

        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (insertAction == null) {
            throw new RuntimeException("insertAction PreparedStatement is null");
        }

        try {
            session.execute(insertAction.bind(tenantId, actionPlugin, actionId, properties));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void addTrigger(String tenantId, Trigger trigger) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(trigger)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        checkTenantId(tenantId, trigger);
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (insertTrigger == null) {
            throw new RuntimeException("insertTrigger PreparedStatement is null");
        }

        try {
            session.execute(insertTrigger.bind(trigger.getName(), trigger.getDescription(),
                    trigger.isAutoDisable(), trigger.isAutoResolve(), trigger.isAutoResolveAlerts(), trigger
                            .getSeverity().name(),
                    trigger.getFiringMatch().name(), trigger.getAutoResolveMatch().name(),
                    trigger.getId(), trigger.isEnabled(), trigger.getTenantId()));

            insertTriggerActions(trigger);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    private void insertTriggerActions(Trigger trigger) throws Exception {
        if (insertTriggerActions == null) {
            throw new RuntimeException("insertTriggerActions PreparedStatement is null");
        }
        if (trigger.getActions() != null) {
            List<ResultSetFuture> futures = trigger.getActions().keySet().stream()
                    .filter(actionPlugin -> trigger.getActions().get(actionPlugin) != null &&
                            !trigger.getActions().get(actionPlugin).isEmpty())
                    .map(actionPlugin -> session.executeAsync(insertTriggerActions.bind(trigger.getTenantId(),
                            trigger.getId(), actionPlugin, trigger.getActions().get(actionPlugin))))
                    .collect(Collectors.toList());
            Futures.allAsList(futures).get();
        }
    }

    @Override
    public void removeTrigger(String tenantId, String triggerId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (deleteDampenings == null || deleteConditions == null || deleteTriggers == null) {
            throw new RuntimeException("delete*Triggers PreparedStatement is null");
        }
        try {
            deleteTags(tenantId, triggerId, null, null);
            List<ResultSetFuture> futures = new ArrayList<>();
            futures.add(session.executeAsync(deleteDampenings.bind(tenantId, triggerId)));
            futures.add(session.executeAsync(deleteConditions.bind(tenantId, triggerId)));
            futures.add(session.executeAsync(deleteTriggers.bind(tenantId, triggerId)));
            Futures.allAsList(futures).get();
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public Trigger updateTrigger(String tenantId, Trigger trigger) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(trigger)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        checkTenantId(tenantId, trigger);
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (updateTrigger == null) {
            throw new RuntimeException("updateTrigger PreparedStatement is null");
        }
        try {
            session.execute(updateTrigger.bind(trigger.getName(), trigger.getDescription(), trigger.isAutoDisable(),
                    trigger.isAutoResolve(), trigger.isAutoResolveAlerts(), trigger.getSeverity().name(),
                    trigger.getFiringMatch().name(), trigger.getAutoResolveMatch().name(), trigger.isEnabled(),
                    trigger.getTenantId(), trigger.getId()));
            deleteTriggerActions(trigger);
            insertTriggerActions(trigger);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (initialized && null != alertsService) {
            alertsService.reloadTrigger(trigger.getTenantId(), trigger.getId());
        }

        notifyListeners(DefinitionsEvent.EventType.TRIGGER_CHANGE);

        return trigger;
    }

    private void deleteTriggerActions(Trigger trigger) throws Exception {
        if (deleteTriggerActions == null) {
            throw new RuntimeException("updateTrigger PreparedStatement is null");
        }
        session.execute(deleteTriggerActions.bind(trigger.getTenantId(), trigger.getId()));
    }

    @Override
    public Trigger getTrigger(String tenantId, String triggerId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectTrigger == null) {
            throw new RuntimeException("selectTrigger PreparedStatement is null");
        }
        Trigger trigger = null;
        try {
            ResultSet rsTrigger = session.execute(selectTrigger.bind(tenantId, triggerId));
            Iterator<Row> itTrigger = rsTrigger.iterator();
            if (itTrigger.hasNext()) {
                Row row = itTrigger.next();
                trigger = mapTrigger(row);
                selectTriggerActions(trigger);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return trigger;
    }

    @Override
    public Collection<Trigger> getTriggers(String tenantId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectTenantTriggers == null) {
            throw new RuntimeException("selectTenantTriggers PreparedStatement is null");
        }
        List<Trigger> triggers = new ArrayList<>();
        try {
            ResultSet rsTriggers = session.execute(selectTenantTriggers.bind(tenantId));
            for (Row row : rsTriggers) {
                Trigger trigger = mapTrigger(row);
                selectTriggerActions(trigger);
                triggers.add(trigger);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return triggers;
    }

    @Override
    public Collection<Trigger> getAllTriggers() throws Exception {
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectAllTriggers == null) {
            throw new RuntimeException("selectAllTriggers PreparedStatement is null");
        }
        List<Trigger> triggers = new ArrayList<>();
        try {
            ResultSet rsTriggers = session.execute(selectAllTriggers.bind());
            for (Row row : rsTriggers) {
                Trigger trigger = mapTrigger(row);
                selectTriggerActions(trigger);
                triggers.add(trigger);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return triggers;
    }

    private void selectTriggerActions(Trigger trigger) throws Exception {
        if (trigger == null) {
            throw new IllegalArgumentException("Trigger must be not null");
        }
        if (selectTriggerActions == null) {
            throw new RuntimeException("selectTriggerActions PreparedStatement is null");
        }
        ResultSet rsTriggerActions = session
                .execute(selectTriggerActions.bind(trigger.getTenantId(), trigger.getId()));
        for (Row row : rsTriggerActions) {
            String actionPlugin = row.getString("actionPlugin");
            Set<String> actions = row.getSet("actions", String.class);
            trigger.addActions(actionPlugin, actions);
        }
    }

    private Trigger mapTrigger(Row row) {
        Trigger trigger = new Trigger();

        trigger.setName(row.getString("name"));
        trigger.setDescription(row.getString("description"));
        trigger.setAutoDisable(row.getBool("autoDisable"));
        trigger.setAutoResolve(row.getBool("autoResolve"));
        trigger.setAutoResolveAlerts(row.getBool("autoResolveAlerts"));
        trigger.setSeverity(Severity.valueOf(row.getString("severity")));
        trigger.setFiringMatch(TriggerTemplate.Match.valueOf(row.getString("firingMatch")));
        trigger.setAutoResolveMatch(TriggerTemplate.Match.valueOf(row.getString("autoResolveMatch")));
        trigger.setId(row.getString("id"));
        trigger.setEnabled(row.getBool("enabled"));
        trigger.setTenantId(row.getString("tenantId"));

        return trigger;
    }

    @Override
    public Trigger copyTrigger(String tenantId, String triggerId, Map<String, String> dataIdMap) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (isEmpty(dataIdMap)) {
            throw new IllegalArgumentException("DataIdMap must be not null");
        }
        Trigger trigger = getTrigger(tenantId, triggerId);
        if (trigger == null) {
            throw new IllegalArgumentException("Trigger not found for tenantId [ " + tenantId + "] and triggerId [" +
                    triggerId + "]");
        }
        // ensure we have a 1-1 mapping for the dataId substitution
        Set<String> dataIdTokens = new HashSet<>();
        Collection<Condition> conditions = getTriggerConditions(tenantId, triggerId, null);
        for (Condition c : conditions) {
            if (c instanceof CompareCondition) {
                dataIdTokens.add(c.getDataId());
                dataIdTokens.add(((CompareCondition) c).getData2Id());
            } else {
                dataIdTokens.add(c.getDataId());
            }
        }
        if (!dataIdTokens.equals(dataIdMap.keySet())) {
            throw new IllegalArgumentException(
                    "DataIdMap must contain the exact dataIds (keyset) expected by the condition set. Expected: "
                            + dataIdMap.keySet() + ", dataIdMap: " + dataIdMap.keySet());
        }
        Collection<Dampening> dampenings = getTriggerDampenings(tenantId, triggerId, null);

        Trigger newTrigger = new Trigger(trigger.getName());
        newTrigger.setName(trigger.getName());
        newTrigger.setDescription(trigger.getDescription());
        newTrigger.setSeverity(trigger.getSeverity());
        newTrigger.setFiringMatch(trigger.getFiringMatch());
        newTrigger.setAutoResolveMatch(trigger.getAutoResolveMatch());
        newTrigger.setActions(trigger.getActions());
        newTrigger.setTenantId(trigger.getTenantId());

        addTrigger(tenantId, newTrigger);

        for (Condition c : conditions) {
            Condition newCondition = null;
            if (c instanceof ThresholdCondition) {
                newCondition = new ThresholdCondition(newTrigger.getId(), c.getTriggerMode(),
                        c.getConditionSetSize(), c.getConditionSetIndex(), dataIdMap.get(c.getDataId()),
                        ((ThresholdCondition) c).getOperator(), ((ThresholdCondition) c).getThreshold());

            } else if (c instanceof ThresholdRangeCondition) {
                newCondition = new ThresholdRangeCondition(newTrigger.getId(), c.getTriggerMode(),
                        c.getConditionSetSize(), c.getConditionSetIndex(), dataIdMap.get(c.getDataId()),
                        ((ThresholdRangeCondition) c).getOperatorLow(),
                        ((ThresholdRangeCondition) c).getOperatorHigh(),
                        ((ThresholdRangeCondition) c).getThresholdLow(),
                        ((ThresholdRangeCondition) c).getThresholdHigh(),
                        ((ThresholdRangeCondition) c).isInRange());

            } else if (c instanceof AvailabilityCondition) {
                newCondition = new AvailabilityCondition(newTrigger.getId(), c.getTriggerMode(),
                        c.getConditionSetSize(), c.getConditionSetIndex(), dataIdMap.get(c.getDataId()),
                        ((AvailabilityCondition) c).getOperator());

            } else if (c instanceof CompareCondition) {
                newCondition = new CompareCondition(newTrigger.getId(), c.getTriggerMode(),
                        c.getConditionSetSize(), c.getConditionSetIndex(), dataIdMap.get(c.getDataId()),
                        ((CompareCondition) c).getOperator(),
                        ((CompareCondition) c).getData2Multiplier(),
                        dataIdMap.get(((CompareCondition) c).getData2Id()));

            } else if (c instanceof StringCondition) {
                newCondition = new StringCondition(newTrigger.getId(), c.getTriggerMode(),
                        c.getConditionSetSize(), c.getConditionSetIndex(), dataIdMap.get(c.getDataId()),
                        ((StringCondition) c).getOperator(), ((StringCondition) c).getPattern(),
                        ((StringCondition) c).isIgnoreCase());
            }
            if (newCondition != null) {
                newCondition.setTenantId(newTrigger.getTenantId());
                addCondition(newTrigger.getTenantId(), newTrigger.getId(), newCondition.getTriggerMode(), newCondition);
            }
        }

        for (Dampening d : dampenings) {
            Dampening newDampening = new Dampening(newTrigger.getId(), d.getTriggerMode(), d.getType(),
                    d.getEvalTrueSetting(), d.getEvalTotalSetting(), d.getEvalTimeSetting());
            newDampening.setTenantId(newTrigger.getTenantId());

            addDampening(newTrigger.getTenantId(), newDampening);
        }

        return newTrigger;
    }

    @Override
    public Dampening addDampening(String tenantId, Dampening dampening) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampening)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        checkTenantId(tenantId, dampening);
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (insertDampening == null) {
            throw new RuntimeException("insertDampening PreparedStatement is null");
        }

        try {
            session.execute(insertDampening.bind(dampening.getTriggerId(), dampening.getTriggerMode().name(),
                    dampening.getType().name(), dampening.getEvalTrueSetting(), dampening.getEvalTotalSetting(),
                    dampening.getEvalTimeSetting(), dampening.getDampeningId(), dampening.getTenantId()));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (initialized && null != alertsService) {
            alertsService.reloadTrigger(dampening.getTenantId(), dampening.getTriggerId());
        }

        notifyListeners(DefinitionsEvent.EventType.DAMPENING_CHANGE);

        return dampening;
    }

    @Override
    public void removeDampening(String tenantId, String dampeningId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampeningId)) {
            throw new IllegalArgumentException("dampeningId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (deleteDampeningId == null) {
            throw new RuntimeException("deleteDampeningId PreparedStatement is null");
        }

        Dampening dampening = getDampening(tenantId, dampeningId);
        if (dampening == null) {
            log.debugf("Ignoring removeDampening(" + dampeningId + "), the Dampening does not exist.");
            return;
        }

        try {
            session.execute(deleteDampeningId.bind(dampening.getTenantId(), dampening.getTriggerId(),
                    dampening.getTriggerMode().name(), dampeningId));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (initialized && null != alertsService) {
            alertsService.reloadTrigger(dampening.getTenantId(), dampening.getTriggerId());
        }

        notifyListeners(DefinitionsEvent.EventType.DAMPENING_CHANGE);
    }

    @Override
    public Dampening updateDampening(String tenantId, Dampening dampening) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampening)) {
            throw new IllegalArgumentException("DampeningId must be not null");
        }
        checkTenantId(tenantId, dampening);
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (updateDampeningId == null) {
            throw new RuntimeException("updateDampeningId PreparedStatement is null");
        }

        try {
            session.execute(updateDampeningId.bind(dampening.getType().name(), dampening.getEvalTrueSetting(),
                    dampening.getEvalTotalSetting(), dampening.getEvalTimeSetting(), dampening.getTenantId(),
                    dampening.getTriggerId(), dampening.getTriggerMode().name(), dampening.getDampeningId()));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (initialized && null != alertsService) {
            alertsService.reloadTrigger(dampening.getTenantId(), dampening.getTriggerId());
        }

        notifyListeners(DefinitionsEvent.EventType.DAMPENING_CHANGE);

        return dampening;
    }

    @Override
    public Dampening getDampening(String tenantId, String dampeningId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(dampeningId)) {
            throw new IllegalArgumentException("DampeningId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectDampeningId == null) {
            throw new RuntimeException("selectDampeningId PreparedStatement is null");
        }

        Dampening dampening = null;
        try {
            ResultSet rsDampening = session.execute(selectDampeningId.bind(tenantId, dampeningId));
            Iterator<Row> itDampening = rsDampening.iterator();
            if (itDampening.hasNext()) {
                Row row = itDampening.next();
                dampening = mapDampening(row);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return dampening;
    }

    @Override
    public Collection<Dampening> getTriggerDampenings(String tenantId, String triggerId, Trigger.Mode triggerMode)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectTriggerDampenings == null || selectTriggerDampeningsMode == null) {
            throw new RuntimeException("selectTriggerDampenings* PreparedStatement is null");
        }
        List<Dampening> dampenings = new ArrayList<>();
        try {
            ResultSet rsDampenings;
            if (triggerMode == null) {
                rsDampenings = session.execute(selectTriggerDampenings.bind(tenantId, triggerId));
            } else {
                rsDampenings = session.execute(selectTriggerDampeningsMode.bind(tenantId, triggerId,
                        triggerMode.name()));
            }
            mapDampenings(rsDampenings, dampenings);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return dampenings;
    }

    @Override
    public Collection<Dampening> getAllDampenings() throws Exception {
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectAllDampenings == null) {
            throw new RuntimeException("selectAllDampenings PreparedStatement is null");
        }
        List<Dampening> dampenings = new ArrayList<>();
        try {
            ResultSet rsDampenings = session.execute(selectAllDampenings.bind());
            mapDampenings(rsDampenings, dampenings);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return dampenings;
    }

    @Override
    public Collection<Dampening> getDampenings(String tenantId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectAllDampeningsByTenant == null) {
            throw new RuntimeException("selectAllDampeningsByTenant PreparedStatement is null");
        }
        List<Dampening> dampenings = new ArrayList<>();
        try {
            ResultSet rsDampenings = session.execute(selectAllDampeningsByTenant.bind(tenantId));
            mapDampenings(rsDampenings, dampenings);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return dampenings;
    }

    private void mapDampenings(ResultSet rsDampenings, List<Dampening> dampenings) throws Exception {
        for (Row row : rsDampenings) {
            Dampening dampening = mapDampening(row);
            dampenings.add(dampening);
        }
    }

    private Dampening mapDampening(Row row) {
        Dampening dampening = new Dampening();
        dampening.setTenantId(row.getString("tenantId"));
        dampening.setTriggerId(row.getString("triggerId"));
        dampening.setTriggerMode(Trigger.Mode.valueOf(row.getString("triggerMode")));
        dampening.setType(Dampening.Type.valueOf(row.getString("type")));
        dampening.setEvalTrueSetting(row.getInt("evalTrueSetting"));
        dampening.setEvalTotalSetting(row.getInt("evalTotalSetting"));
        dampening.setEvalTimeSetting(row.getLong("evalTimeSetting"));
        return dampening;
    }

    @Override
    public Collection<Condition> addCondition(String tenantId, String triggerId, Trigger.Mode triggerMode,
            Condition condition) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (triggerMode == null) {
            throw new IllegalArgumentException("TriggerMode must be not null");
        }
        if (condition == null) {
            throw new IllegalArgumentException("Condition must be not null");
        }
        Collection<Condition> conditions = getTriggerConditions(tenantId, triggerId, triggerMode);
        conditions.add(condition);
        int i = 0;
        for (Condition c : conditions) {
            c.setConditionSetSize(conditions.size());
            c.setConditionSetIndex(++i);
        }

        return setConditions(tenantId, triggerId, triggerMode, conditions);
    }

    @Override
    public Collection<Condition> removeCondition(String tenantId, String conditionId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(conditionId)) {
            throw new IllegalArgumentException("ConditionId must be not null");
        }

        Condition condition = getCondition(tenantId, conditionId);
        if (null == condition) {
            log.debugf("Ignoring removeCondition [%s], the condition does not exist.", conditionId);
            return null;
        }

        String triggerId = condition.getTriggerId();
        Trigger.Mode triggerMode = condition.getTriggerMode();
        Collection<Condition> conditions = getTriggerConditions(tenantId, triggerId, triggerMode);

        int i = 0;
        int size = conditions.size() - 1;
        Collection<Condition> newConditions = new ArrayList<>(size);
        for (Condition c : conditions) {
            if (!c.getConditionId().equals(conditionId)) {
                c.setConditionSetSize(conditions.size());
                c.setConditionSetIndex(++i);
                newConditions.add(c);
            }
        }
        return setConditions(tenantId, triggerId, triggerMode, newConditions);
    }

    @Override
    public Collection<Condition> updateCondition(String tenantId, Condition condition) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (condition == null) {
            throw new IllegalArgumentException("Condition must be not null");
        }

        String conditionId = condition.getConditionId();
        if (isEmpty(conditionId)) {
            throw new IllegalArgumentException("ConditionId must be not null");
        }

        Condition existingCondition = getCondition(tenantId, conditionId);
        if (null == existingCondition) {
            throw new IllegalArgumentException("ConditionId [" + conditionId + "] on tenant " + tenantId +
                    " does not exist.");
        }
        String triggerId = existingCondition.getTriggerId();
        Trigger.Mode triggerMode = existingCondition.getTriggerMode();
        Collection<Condition> conditions = getTriggerConditions(tenantId, triggerId, triggerMode);

        int size = conditions.size();
        Collection<Condition> newConditions = new ArrayList<>(size);
        for (Condition c : conditions) {
            if (c.getConditionId().equals(conditionId)) {
                newConditions.add(condition);
            } else {
                newConditions.add(c);
            }
        }

        return setConditions(tenantId, triggerId, triggerMode, newConditions);
    }

    @Override
    public Collection<Condition> setConditions(String tenantId, String triggerId, Trigger.Mode triggerMode,
            Collection<Condition> conditions) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (triggerMode == null) {
            throw new IllegalArgumentException("TriggerMode must be not null");
        }
        if (conditions == null) {
            throw new IllegalArgumentException("Conditions must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (insertAvailabilityCondition == null
                || insertCompareCondition == null
                || insertStringCondition == null
                || insertThresholdCondition == null
                || insertThresholdRangeCondition == null) {
            throw new RuntimeException("insert*Condition PreparedStatement is null");
        }
        // Get rid of the prior condition set
        removeConditions(tenantId, triggerId, triggerMode);

        // Now add the new condition set
        try {
            List<String> dataIds = new ArrayList<>(2);

            List<ResultSetFuture> futures = new ArrayList<>();

            int i = 0;
            for (Condition cond : conditions) {
                cond.setTenantId(tenantId);
                cond.setTriggerId(triggerId);
                cond.setTriggerMode(triggerMode);
                cond.setConditionSetSize(conditions.size());
                cond.setConditionSetIndex(++i);

                dataIds.add(cond.getDataId());

                if (cond instanceof AvailabilityCondition) {

                    AvailabilityCondition aCond = (AvailabilityCondition) cond;
                    futures.add(session.executeAsync(insertAvailabilityCondition.bind(aCond.getTenantId(), aCond
                            .getTriggerId(),
                            aCond.getTriggerMode().name(), aCond.getConditionSetSize(), aCond.getConditionSetIndex(),
                            aCond.getConditionId(), aCond.getDataId(), aCond.getOperator().name())));

                } else if (cond instanceof CompareCondition) {

                    CompareCondition cCond = (CompareCondition) cond;
                    dataIds.add(cCond.getData2Id());
                    futures.add(session.executeAsync(insertCompareCondition.bind(cCond.getTenantId(),
                            cCond.getTriggerId(), cCond.getTriggerMode().name(), cCond.getConditionSetSize(),
                            cCond.getConditionSetIndex(), cCond.getConditionId(), cCond.getDataId(),
                            cCond.getOperator().name(), cCond.getData2Id(), cCond.getData2Multiplier())));

                } else if (cond instanceof StringCondition) {

                    StringCondition sCond = (StringCondition) cond;
                    futures.add(session.executeAsync(insertStringCondition.bind(sCond.getTenantId(), sCond
                            .getTriggerId(), sCond.getTriggerMode().name(), sCond.getConditionSetSize(),
                            sCond.getConditionSetIndex(), sCond.getConditionId(), sCond.getDataId(),
                            sCond.getOperator().name(), sCond.getPattern(), sCond.isIgnoreCase())));

                } else if (cond instanceof ThresholdCondition) {

                    ThresholdCondition tCond = (ThresholdCondition) cond;
                    futures.add(session.executeAsync(insertThresholdCondition.bind(tCond.getTenantId(),
                            tCond.getTriggerId(), tCond.getTriggerMode().name(), tCond.getConditionSetSize(),
                            tCond.getConditionSetIndex(), tCond.getConditionId(), tCond.getDataId(),
                            tCond.getOperator().name(), tCond.getThreshold())));

                } else if (cond instanceof ThresholdRangeCondition) {

                    ThresholdRangeCondition rCond = (ThresholdRangeCondition) cond;
                    futures.add(session.executeAsync(insertThresholdRangeCondition.bind(rCond.getTenantId(),
                            rCond.getTriggerId(), rCond.getTriggerMode().name(), rCond.getConditionSetSize(),
                            rCond.getConditionSetIndex(), rCond.getConditionId(), rCond.getDataId(),
                            rCond.getOperatorLow().name(), rCond.getOperatorHigh().name(), rCond.getThresholdLow(),
                            rCond.getThresholdHigh(), rCond.isInRange())));
                }

                // generate the automatic dataId tags for search
                for (String dataId : dataIds) {
                    insertTag(cond.getTenantId(), cond.getTriggerId(), "dataId", dataId, false);
                }
                dataIds.clear();
            }
            Futures.allAsList(futures).get();

        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        if (initialized && alertsService != null) {
            alertsService.reloadTrigger(tenantId, triggerId);
        }

        notifyListeners(DefinitionsEvent.EventType.CONDITION_CHANGE);

        return conditions;
    }

    private void insertTag(String tenantId, String triggerId, String category, String name, boolean visible)
            throws Exception {

        // If the desired Tag already exists just return
        if (!getTags(tenantId, triggerId, category, name).isEmpty()) {
            return;
        }

        session.execute(insertTag.bind(tenantId, triggerId, category, name, visible));
        insertTriggerByTagIndex(tenantId, category, name, triggerId);
    }

    private void insertTriggerByTagIndex(String tenantId, String category, String name, String triggerId) {
        Set<String> triggers = getTriggersByTags(tenantId, category, name);
        triggers = new HashSet<>(triggers);
        if (triggers.isEmpty()) {
            triggers.add(triggerId);
            session.execute(insertTagsTriggers.bind(tenantId, category, name, triggers));
        } else {
            if (!triggers.contains(triggerId)) {
                triggers.add(triggerId);
                session.execute(updateTagsTriggers.bind(triggers, tenantId, name));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> getTriggersByTags(String tenantId, String category, String name) {
        Set triggerTags = new HashSet<>();

        ResultSet rsTriggersTags = session.execute(selectTagsTriggers.bind(tenantId, category, name));
        for (Row row : rsTriggersTags) {
            triggerTags = row.getSet("triggers", String.class);
        }
        return triggerTags;
    }

    private List<Tag> getTags(String tenantId, String triggerId, String category, String name)
            throws Exception {
        BoundStatement boundTags;
        if (!isEmpty(category) && !isEmpty(name)) {
            boundTags = selectTagsByCategoryAndName.bind(tenantId, triggerId, category, name);
        } else if (!isEmpty(category)) {
            boundTags = selectTagsByCategory.bind(tenantId, triggerId, category);
        } else if (!isEmpty(name)) {
            boundTags = selectTagsByName.bind(tenantId, triggerId, name);
        } else {
            boundTags = selectTags.bind(tenantId, triggerId);
        }

        List<Tag> tags = new ArrayList<>();
        ResultSet rsTags = session.execute(boundTags);
        for (Row row : rsTags) {
            Tag tag = new Tag();
            tag.setTenantId(row.getString("tenantId"));
            tag.setTriggerId(row.getString("triggerId"));
            tag.setCategory(row.getString("category"));
            tag.setName(row.getString("name"));
            tag.setVisible(row.getBool("visible"));
            tags.add(tag);
        }
        return tags;
    }

    private void notifyListeners(DefinitionsEvent.EventType eventType) {
        DefinitionsEvent de = new DefinitionsEvent(eventType);
        for (DefinitionsListener dl : listeners) {
            log.debugf("Notified Listener %s", eventType.name());
            dl.onChange(de);
        }
    }

    private void removeConditions(String tenantId, String triggerId, Trigger.Mode triggerMode) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must not be null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must not be null");
        }
        if (triggerMode == null) {
            throw new IllegalArgumentException("TriggerMode must not be null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (deleteConditionsMode == null) {
            throw new RuntimeException("deleteConditionsMode PreparedStatement is null");
        }
        try {
            session.execute(deleteConditionsMode.bind(tenantId, triggerId, triggerMode.name()));

            // if removing conditions remove the automatically-added dataId tags
            deleteTags(tenantId, triggerId, "dataId", null);

        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    private void deleteTags(String tenantId, String triggerId, String category, String name) throws Exception {
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        BoundStatement boundTags;
        if (!isEmpty(name)) {
            boundTags = deleteTagsByName.bind(tenantId, triggerId, name);
        } else {
            boundTags = deleteTags.bind(tenantId, triggerId);
        }
        try {
            deleteTriggerByTagIndex(tenantId, triggerId, category, name);
            session.execute(boundTags);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    private void deleteTriggerByTagIndex(String tenantId, String triggerId, String category, String name)
            throws Exception {
        List<Tag> tags;
        if (category == null || name == null) {
            tags = getTriggerTags(tenantId, triggerId, category);
        } else {
            tags = new ArrayList<>();
            Tag singleTag = new Tag();
            singleTag.setTenantId(tenantId);
            singleTag.setCategory(category);
            singleTag.setName(name);
            tags.add(singleTag);
        }

        for (Tag tag : tags) {
            Set<String> triggers = getTriggersByTags(tag.getTenantId(), tag.getCategory(), tag.getName());
            if (triggers.size() > 1) {
                Set<String> updateTriggers = new HashSet<>(triggers);
                updateTriggers.remove(triggerId);
                session.execute(updateTagsTriggers.bind(triggers, tag.getTenantId(), tag.getName()));
            } else {
                session.execute(deleteTagsTriggers.bind(tag.getTenantId(), tag.getName()));
            }
        }
    }

    @Override
    public Condition getCondition(String tenantId, String conditionId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(conditionId)) {
            throw new IllegalArgumentException("conditionId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectConditionId == null) {
            throw new RuntimeException("selectConditionId PreparedStatement is null");
        }
        Condition condition = null;
        try {
            ResultSet rsCondition = session.execute(selectConditionId.bind(tenantId, conditionId));
            Iterator<Row> itCondition = rsCondition.iterator();
            if (itCondition.hasNext()) {
                Row row = itCondition.next();
                condition = mapCondition(row);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        return condition;
    }

    @Override
    public Collection<Condition> getTriggerConditions(String tenantId, String triggerId, Trigger.Mode triggerMode)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("triggerId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectTriggerConditions == null || selectTriggerConditionsTriggerMode == null) {
            throw new RuntimeException("selectTriggerConditions* PreparedStatement is null");
        }
        List<Condition> conditions = new ArrayList<>();
        try {
            ResultSet rsConditions;
            if (triggerMode == null) {
                rsConditions = session.execute(selectTriggerConditions.bind(tenantId, triggerId));
            } else {
                rsConditions = session.execute(selectTriggerConditionsTriggerMode.bind(tenantId, triggerId,
                        triggerMode.name()));
            }
            mapConditions(rsConditions, conditions);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        return conditions;
    }

    @Override
    public Collection<Condition> getAllConditions() throws Exception {
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectAllConditions == null) {
            throw new RuntimeException("selectAllConditions PreparedStatement is null");
        }
        List<Condition> conditions = new ArrayList<>();
        try {
            ResultSet rsConditions = session.execute(selectAllConditions.bind());
            mapConditions(rsConditions, conditions);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return conditions;
    }

    @Override
    public Collection<Condition> getConditions(String tenantId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectAllConditionsByTenant == null) {
            throw new RuntimeException("selectAllConditionsByTenant PreparedStatement is null");
        }
        List<Condition> conditions = new ArrayList<>();
        try {
            ResultSet rsConditions = session.execute(selectAllConditionsByTenant.bind(tenantId));
            mapConditions(rsConditions, conditions);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return conditions;
    }

    private void mapConditions(ResultSet rsConditions, List<Condition> conditions) throws Exception {
        for (Row row : rsConditions) {
            Condition condition = mapCondition(row);
            if (condition != null) {
                conditions.add(condition);
            }
        }
    }

    private Condition mapCondition(Row row) throws Exception {
        Condition condition = null;
        String type = row.getString("type");
        if (type != null && !type.isEmpty()) {
            if (type.equals(Condition.Type.AVAILABILITY.name())) {
                AvailabilityCondition aCondition = new AvailabilityCondition();
                aCondition.setTenantId(row.getString("tenantId"));
                aCondition.setTriggerId(row.getString("triggerId"));
                aCondition.setTriggerMode(Trigger.Mode.valueOf(row.getString("triggerMode")));
                aCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                aCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                aCondition.setDataId(row.getString("dataId"));
                aCondition.setOperator(AvailabilityCondition.Operator.valueOf(row.getString("operator")));
                condition = aCondition;
            } else if (type.equals(Condition.Type.COMPARE.name())) {
                CompareCondition cCondition = new CompareCondition();
                cCondition.setTenantId(row.getString("tenantId"));
                cCondition.setTriggerId(row.getString("triggerId"));
                cCondition.setTriggerMode(Trigger.Mode.valueOf(row.getString("triggerMode")));
                cCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                cCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                cCondition.setDataId(row.getString("dataId"));
                cCondition.setOperator(CompareCondition.Operator.valueOf(row.getString("operator")));
                cCondition.setData2Id(row.getString("data2Id"));
                cCondition.setData2Multiplier(row.getDouble("data2Multiplier"));
                condition = cCondition;
            } else if (type.equals(Condition.Type.STRING.name())) {
                StringCondition sCondition = new StringCondition();
                sCondition.setTenantId(row.getString("tenantId"));
                sCondition.setTriggerId(row.getString("triggerId"));
                sCondition.setTriggerMode(Trigger.Mode.valueOf(row.getString("triggerMode")));
                sCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                sCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                sCondition.setDataId(row.getString("dataId"));
                sCondition.setOperator(StringCondition.Operator.valueOf(row.getString("operator")));
                sCondition.setPattern(row.getString("pattern"));
                sCondition.setIgnoreCase(row.getBool("ignoreCase"));
                condition = sCondition;
            } else if (type.equals(Condition.Type.THRESHOLD.name())) {
                ThresholdCondition tCondition = new ThresholdCondition();
                tCondition.setTenantId(row.getString("tenantId"));
                tCondition.setTriggerId(row.getString("triggerId"));
                tCondition.setTriggerMode(Trigger.Mode.valueOf(row.getString("triggerMode")));
                tCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                tCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                tCondition.setDataId(row.getString("dataId"));
                tCondition.setOperator(ThresholdCondition.Operator.valueOf(row.getString("operator")));
                tCondition.setThreshold(row.getDouble("threshold"));
                condition = tCondition;
            } else if (type.equals(Condition.Type.RANGE.name())) {
                ThresholdRangeCondition rCondition = new ThresholdRangeCondition();
                rCondition.setTenantId(row.getString("tenantId"));
                rCondition.setTriggerId(row.getString("triggerId"));
                rCondition.setTriggerMode(Trigger.Mode.valueOf(row.getString("triggerMode")));
                rCondition.setConditionSetSize(row.getInt("conditionSetSize"));
                rCondition.setConditionSetIndex(row.getInt("conditionSetIndex"));
                rCondition.setDataId(row.getString("dataId"));
                rCondition.setOperatorLow(ThresholdRangeCondition.Operator.valueOf(row.getString
                        ("operatorLow")));
                rCondition.setOperatorHigh(ThresholdRangeCondition.Operator.valueOf(row.getString
                        ("operatorHigh")));
                rCondition.setThresholdLow(row.getDouble("thresholdLow"));
                rCondition.setThresholdHigh(row.getDouble("thresholdHigh"));
                rCondition.setInRange(row.getBool("inRange"));
                condition = rCondition;
            } else {
                log.debugf("Wrong condition type found: " + type);
            }
        } else {
            log.debugf("Wrong condition type: null or empty");
        }
        return condition;
    }

    @Override
    public void addActionPlugin(String actionPlugin, Set<String> properties) throws Exception {
        if (actionPlugin == null || actionPlugin.isEmpty()) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("properties must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (insertActionPlugin == null) {
            throw new RuntimeException("insertActionPlugin PreparedStatement is null");
        }
        try {
            session.execute(insertActionPlugin.bind(actionPlugin, properties));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void removeActionPlugin(String actionPlugin) throws Exception {
        if (actionPlugin == null || actionPlugin.isEmpty()) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (deleteActionPlugin == null) {
            throw new RuntimeException("deleteActionPlugin PreparedStatement is null");
        }
        try {
            session.execute(deleteActionPlugin.bind(actionPlugin));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void updateActionPlugin(String actionPlugin, Set<String> properties) throws Exception {
        if (actionPlugin == null || actionPlugin.isEmpty()) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("properties must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (updateActionPlugin == null) {
            throw new RuntimeException("updateActionPlugin PreparedStatement is null");
        }
        try {
            session.execute(updateActionPlugin.bind(properties, actionPlugin));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public Collection<String> getActionPlugins() throws Exception {
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectActionPlugins == null) {
            throw new RuntimeException("selectActionPlugins PreparedStatement is null");
        }
        ArrayList<String> actionPlugins = new ArrayList<>();
        try {
            ResultSet rsActionPlugins = session.execute(selectActionPlugins.bind());
            for (Row row : rsActionPlugins) {
                actionPlugins.add(row.getString("actionPlugin"));
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actionPlugins;
    }

    @Override
    public Set<String> getActionPlugin(String actionPlugin) throws Exception {
        if (actionPlugin == null || actionPlugin.isEmpty()) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectActionPlugin == null) {
            throw new RuntimeException("selectActionPlugin PreparedStatement is null");
        }
        Set<String> properties = null;
        try {
            ResultSet rsActionPlugin = session.execute(selectActionPlugin.bind(actionPlugin));
            Iterator<Row> itActionPlugin = rsActionPlugin.iterator();
            if (itActionPlugin.hasNext()) {
                Row row = itActionPlugin.next();
                properties = row.getSet("properties", String.class);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return properties;
    }

    @Override
    public void removeAction(String tenantId, String actionPlugin, String actionId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("ActionPlugin must be not null");
        }
        if (isEmpty(actionId)) {
            throw new IllegalArgumentException("ActionId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (deleteAction == null) {
            throw new RuntimeException("deleteAction PreparedStatement is null");
        }
        try {
            session.execute(deleteAction.bind(tenantId, actionPlugin, actionId));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void updateAction(String tenantId, String actionPlugin, String actionId, Map<String, String> properties)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("ActionPlugin must be not null");
        }
        if (isEmpty(actionId)) {
            throw new IllegalArgumentException("ActionId must be not null");
        }
        if (properties == null || properties.isEmpty()) {
            throw new IllegalArgumentException("Properties must be not null");
        }
        properties.put("actionId", actionId);
        properties.put("actionPlugin", actionPlugin);

        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (updateAction == null) {
            throw new RuntimeException("updateAction PreparedStatement is null");
        }
        try {
            session.execute(updateAction.bind(properties, tenantId, actionPlugin, actionId));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public Map<String, Map<String, Set<String>>> getAllActions() throws Exception {
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectAllActions == null) {
            throw new RuntimeException("selectAllActions PreparedStatement is null");
        }
        Map<String, Map<String, Set<String>>> actions = new HashMap<>();
        try {
            ResultSet rsActions = session.execute(selectAllActions.bind());
            for (Row row : rsActions) {
                String tenantId = row.getString("tenantId");
                String actionPlugin = row.getString("actionPlugin");
                String actionId = row.getString("actionId");
                if (actions.get(tenantId) == null) {
                    actions.put(tenantId, new HashMap<>());
                }
                if (actions.get(tenantId).get(actionPlugin) == null) {
                    actions.get(tenantId).put(actionPlugin, new HashSet<>());
                }
                actions.get(tenantId).get(actionPlugin).add(actionId);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actions;
    }

    @Override
    public Map<String, Set<String>> getActions(String tenantId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectAllActionsByTenant == null) {
            throw new RuntimeException("selectAllActionsByTenant PreparedStatement is null");
        }
        Map<String, Set<String>> actions = new HashMap<>();
        try {
            ResultSet rsActions = session.execute(selectAllActionsByTenant.bind(tenantId));
            for (Row row : rsActions) {
                String actionPlugin = row.getString("actionPlugin");
                String actionId = row.getString("actionId");
                if (actions.get(actionPlugin) == null) {
                    actions.put(actionPlugin, new HashSet<>());
                }
                actions.get(actionPlugin).add(actionId);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actions;
    }

    @Override
    public Collection<String> getActions(String tenantId, String actionPlugin) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("actionPlugin must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectActionsPlugin == null) {
            throw new RuntimeException("selectActionsPlugin PreparedStatement is null");
        }
        ArrayList<String> actions = new ArrayList<>();
        try {
            ResultSet rsActions = session.execute(selectActionsPlugin.bind(tenantId, actionPlugin));
            for (Row row : rsActions) {
                actions.add(row.getString("actionId"));
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return actions;
    }

    @Override
    public Map<String, String> getAction(String tenantId, String actionPlugin, String actionId) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(actionPlugin)) {
            throw new IllegalArgumentException("ActionPlugin must be not null");
        }
        if (isEmpty(actionId)) {
            throw new IllegalArgumentException("actionId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        if (selectAction == null) {
            throw new RuntimeException("selectAction PreparedStatement is null");
        }
        Map<String, String> properties = null;
        try {
            ResultSet rsAction = session.execute(selectAction.bind(tenantId, actionPlugin, actionId));
            Iterator<Row> itAction = rsAction.iterator();
            if (itAction.hasNext()) {
                Row row = itAction.next();
                properties = row.getMap("properties", String.class, String.class);
            }
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return properties;
    }

    @Override
    public void addTag(String tenantId, Tag tag) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (tag == null) {
            throw new IllegalArgumentException("Tag must be not null");
        }
        if (tag.getTriggerId() == null || tag.getTriggerId().trim().isEmpty()) {
            throw new IllegalArgumentException("Tag TriggerId must be not null or empty");
        }
        if (tag.getName() == null || tag.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tag Name must be not null or empty");
        }
        checkTenantId(tenantId, tag);
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        try {
            insertTag(tag.getTenantId(), tag.getTriggerId(), tag.getCategory(), tag.getName(), tag.isVisible());
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public void removeTags(String tenantId, String triggerId, String category, String name) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        try {
            deleteTags(tenantId, triggerId, category, name);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public List<Tag> getTriggerTags(String tenantId, String triggerId, String category) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(triggerId)) {
            throw new IllegalArgumentException("TriggerId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }

        List<Tag> tags;
        try {
            tags = getTags(tenantId, triggerId, category, null);
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return tags;
    }

    @Override
    public void registerListener(DefinitionsListener listener) {
        listeners.add(listener);
    }

    private boolean isEmpty(Trigger trigger) {
        return trigger == null || trigger.getId() == null || trigger.getId().trim().isEmpty();
    }

    private boolean isEmpty(Dampening dampening) {
        return dampening == null || dampening.getTriggerId() == null || dampening.getTriggerId().trim().isEmpty() ||
                dampening.getDampeningId() == null || dampening.getDampeningId().trim().isEmpty();
    }

    private boolean isEmpty(String id) {
        return id == null || id.trim().isEmpty();
    }

    public boolean isEmpty(Map<String, String> map) {
        return map == null || map.isEmpty();
    }

    /*
        The attribute tenantId is part of the Trigger object.
        But it is also important to have it specifically on the services calls.
        So, in case that a tenantId parameter does not match with trigger.tenantId attribute,
        this last one will be overwritten with the parameter.
     */
    private void checkTenantId(String tenantId, Object obj) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return;
        }
        if (obj == null) {
            return;
        }
        if (obj instanceof Trigger) {
            Trigger trigger = (Trigger) obj;
            if (trigger.getTenantId() == null || !trigger.getTenantId().equals(tenantId)) {
                trigger.setTenantId(tenantId);
            }
        } else if (obj instanceof Dampening) {
            Dampening dampening = (Dampening) obj;
            if (dampening.getTenantId() == null || !dampening.getTenantId().equals(tenantId)) {
                dampening.setTenantId(tenantId);
            }
        } else if (obj instanceof Tag) {
            Tag tag = (Tag) obj;
            if (tag.getTenantId() == null || !tag.getTenantId().equals(tenantId)) {
                tag.setTenantId(tenantId);
            }
        }
    }

}
