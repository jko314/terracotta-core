/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.management.service.impl;

import static com.terracotta.management.service.impl.util.RemoteManagementSource.toCsv;

import org.terracotta.management.ServiceExecutionException;
import org.terracotta.management.resource.Representable;

import com.tc.config.schema.L2Info;
import com.tc.config.schema.ServerGroupInfo;
import com.tc.config.schema.setup.TopologyReloadStatus;
import com.tc.objectserver.api.BackupManager;
import com.tc.objectserver.api.GCStats;
import com.tc.operatorevent.TerracottaOperatorEvent;
import com.tc.operatorevent.TerracottaOperatorEventImpl;
import com.terracotta.management.resource.BackupEntityV2;
import com.terracotta.management.resource.ConfigEntityV2;
import com.terracotta.management.resource.LogEntityV2;
import com.terracotta.management.resource.MBeanEntityV2;
import com.terracotta.management.resource.OperatorEventEntityV2;
import com.terracotta.management.resource.ServerEntityV2;
import com.terracotta.management.resource.ServerGroupEntityV2;
import com.terracotta.management.resource.StatisticsEntityV2;
import com.terracotta.management.resource.ThreadDumpEntityV2;
import com.terracotta.management.resource.TopologyEntityV2;
import com.terracotta.management.resource.TopologyReloadStatusEntityV2;
import com.terracotta.management.security.SecurityContextService;
import com.terracotta.management.service.TimeoutService;
import com.terracotta.management.service.impl.util.LocalManagementSource;
import com.terracotta.management.service.impl.util.ManagementSourceException;
import com.terracotta.management.service.impl.util.RemoteManagementSource;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.Notification;
import javax.management.ObjectName;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.UriBuilder;

/**
 * @author Ludovic Orban
 */
public class ServerManagementServiceV2 {

  private static final String[] SERVER_ENTITY_ATTRIBUTE_NAMES = new String[] { "Version", "BuildID",
      "DescriptionOfCapabilities", "PersistenceMode", "FailoverMode", "TSAListenPort", "TSAGroupPort", "State",
      "StartTime", "ActivateTime", "Restartable", "ResourceState" };

  private static final String[] SERVER_STATS_ATTRIBUTE_NAMES = new String[] { "LiveObjectCount",
      "ReadOperationRate", "WriteOperationRate", "OffheapMaxSize", "OffheapReservedSize", "OffheapUsedSize",
      "EvictionRate", "ExpirationRate", "StorageStats" };


  private final LocalManagementSource localManagementSource;
  private final ExecutorService executorService;
  private final TimeoutService timeoutService;
  private final RemoteManagementSource remoteManagementSource;
  private final SecurityContextService securityContextService;

  public ServerManagementServiceV2(ExecutorService executorService, TimeoutService timeoutService, LocalManagementSource localManagementSource, RemoteManagementSource remoteManagementSource, SecurityContextService securityContextService) {
    this.executorService = executorService;
    this.timeoutService = timeoutService;
    this.localManagementSource = localManagementSource;
    this.remoteManagementSource = remoteManagementSource;
    this.securityContextService = securityContextService;
  }

  public boolean isEnterpriseEdition() throws ServiceExecutionException {
    try {
      return localManagementSource.isEnterpriseEdition();
    } catch (ManagementSourceException e) {
      throw new ServiceExecutionException("error making JMX call", e);
    }
  }

  public Collection<String> getL2Urls() throws ServiceExecutionException {
    try {
      return localManagementSource.getServerUrls().values();
    } catch (ManagementSourceException e) {
      throw new ServiceExecutionException("error making JMX call", e);
    }
  }

  public Collection<ThreadDumpEntityV2> serversThreadDump(Set<String> serverNames) throws ServiceExecutionException {
    return forEachServer("serversThreadDump", serverNames, new ForEachServer<ThreadDumpEntityV2>() {
      @Override
      public Collection<ThreadDumpEntityV2> queryLocalServer(L2Info member) {
        ThreadDumpEntityV2 threadDumpEntityV2 = new ThreadDumpEntityV2();
        threadDumpEntityV2.setVersion(localManagementSource.getVersion());
        threadDumpEntityV2.setSourceId(member.name());
        threadDumpEntityV2.setNodeType(ThreadDumpEntityV2.NodeType.SERVER);
        try {
          threadDumpEntityV2.setDump(localManagementSource.serverThreadDump());
        } catch (ManagementSourceException mse) {
          threadDumpEntityV2.setDump("Unavailable");
        }
        return Collections.singleton(threadDumpEntityV2);
      }

      @Override
      public Collection<ThreadDumpEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("diagnostics")
            .path("threadDump")
            .path("servers")
            .matrixParam("names", member.name());

        try {
          return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), ThreadDumpEntityV2.class);
        } catch (ProcessingException che) {
          ThreadDumpEntityV2 threadDumpEntityV2 = new ThreadDumpEntityV2();
          threadDumpEntityV2.setVersion(localManagementSource.getVersion());
          threadDumpEntityV2.setSourceId(member.name());
          threadDumpEntityV2.setNodeType(ThreadDumpEntityV2.NodeType.SERVER);
          threadDumpEntityV2.setDump("Unavailable");
          return Collections.singleton(threadDumpEntityV2);
        }
      }
    });
  }

  public Collection<StatisticsEntityV2> getServersStatistics(Set<String> serverNames, Set<String> attributesToShow) throws ServiceExecutionException {
    final String[] mbeanAttributeNames = (attributesToShow == null) ?
        SERVER_STATS_ATTRIBUTE_NAMES :
        new ArrayList<String>(attributesToShow).toArray(new String[attributesToShow.size()]);

    return forEachServer("getServersStatistics", serverNames, new ForEachServer<StatisticsEntityV2>() {
      @Override
      public Collection<StatisticsEntityV2> queryLocalServer(L2Info member) {
        StatisticsEntityV2 statisticsEntityV2 = new StatisticsEntityV2();
        statisticsEntityV2.setSourceId(member.name());
        statisticsEntityV2.setVersion(localManagementSource.getVersion());
        try {
          statisticsEntityV2.getStatistics().putAll(localManagementSource.getDsoAttributes(mbeanAttributeNames));
        } catch (ManagementSourceException e) {
          statisticsEntityV2.getStatistics().put("Error", e.getMessage());
        }
        return Collections.singleton(statisticsEntityV2);
      }

      @Override
      public Collection<StatisticsEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("statistics")
            .path("servers")
            .matrixParam("names", member.name());

        return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), StatisticsEntityV2.class);
      }
    });
  }

  public Collection<StatisticsEntityV2> getDgcStatistics(Set<String> serverNames, int maxDgcStatsEntries) throws ServiceExecutionException {
    return forEachServer("getDgcStatistics", serverNames, maxDgcStatsEntries, new ForEachServer<StatisticsEntityV2>() {
      @Override
      public Collection<StatisticsEntityV2> queryLocalServer(L2Info member) {
        Collection<StatisticsEntityV2> localResult = new ArrayList<StatisticsEntityV2>();
        try {
          GCStats[] attributes = localManagementSource.getGcStats();
          for (GCStats gcStat : attributes) {
            StatisticsEntityV2 statisticsEntityV2 = new StatisticsEntityV2();
            statisticsEntityV2.setSourceId(member.name());
            statisticsEntityV2.setVersion(localManagementSource.getVersion());

            statisticsEntityV2.getStatistics().put("Iteration", gcStat.getIteration());
            statisticsEntityV2.getStatistics().put("ActualGarbageCount", gcStat.getActualGarbageCount());
            statisticsEntityV2.getStatistics().put("BeginObjectCount", gcStat.getBeginObjectCount());
            statisticsEntityV2.getStatistics().put("CandidateGarbageCount", gcStat.getCandidateGarbageCount());
            statisticsEntityV2.getStatistics().put("ElapsedTime", gcStat.getElapsedTime());
            statisticsEntityV2.getStatistics().put("EndObjectCount", gcStat.getEndObjectCount());
            statisticsEntityV2.getStatistics().put("MarkStageTime", gcStat.getMarkStageTime());
            statisticsEntityV2.getStatistics().put("PausedStageTime", gcStat.getPausedStageTime());
            statisticsEntityV2.getStatistics().put("StartTime", gcStat.getStartTime());
            statisticsEntityV2.getStatistics().put("Status", gcStat.getStatus());
            statisticsEntityV2.getStatistics().put("Type", gcStat.getType());

            localResult.add(statisticsEntityV2);
          }
        } catch (ManagementSourceException e) {
          StatisticsEntityV2 statisticsEntityV2 = new StatisticsEntityV2();
          statisticsEntityV2.setSourceId(member.name());
          statisticsEntityV2.setVersion(localManagementSource.getVersion());

          statisticsEntityV2.getStatistics().put("Error", e.getMessage());

          localResult.add(statisticsEntityV2);
        }
        return localResult;
      }

      @Override
      public Collection<StatisticsEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("statistics")
            .path("dgc")
            .matrixParam("serverNames", member.name());

        return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), StatisticsEntityV2.class);
      }
    });
  }

  public Collection<ConfigEntityV2> getServerConfigs(final Set<String> serverNames) throws ServiceExecutionException {
    return forEachServer("getServerConfigs", serverNames, new ForEachServer<ConfigEntityV2>() {
      @Override
      public Collection<ConfigEntityV2> queryLocalServer(L2Info member) {
        ConfigEntityV2 configEntityV2 = new ConfigEntityV2();
        configEntityV2.setVersion(localManagementSource.getVersion());
        configEntityV2.setSourceId(member.name());
        try {
          configEntityV2.getAttributes().putAll(localManagementSource.getServerInfoAttributes());
        } catch (ManagementSourceException mse) {
          configEntityV2.getAttributes().put("Error", mse.getMessage());
        }
        return Collections.singleton(configEntityV2);
      }

      @Override
      public Collection<ConfigEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("configurations")
            .path("servers")
            .matrixParam("names", member.name());

        try {
          return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), ConfigEntityV2.class);
        } catch (ProcessingException che) {
          ConfigEntityV2 configEntityV2 = new ConfigEntityV2();
          configEntityV2.setVersion(localManagementSource.getVersion());
          configEntityV2.setSourceId(member.name());
          configEntityV2.getAttributes().put("Error", che.getMessage());
          return Collections.singleton(configEntityV2);
        }
      }
    });
  }

  public Collection<BackupEntityV2> getBackupsStatus(Set<String> serverNames) throws ServiceExecutionException {
    return forEachServer("getBackupsStatus", serverNames, new ForEachServer<BackupEntityV2>() {
      @Override
      public Collection<BackupEntityV2> queryLocalServer(L2Info member) {
        if (!localManagementSource.isActiveCoordinator()) {
          return null;
        }

        Collection<BackupEntityV2> localResult = new ArrayList<BackupEntityV2>();
        Map<String, String> backups = localManagementSource.getBackupStatuses();
        for (String name : backups.keySet()) {
          String status = backups.get(name);
          BackupEntityV2 backupEntityV2 = new BackupEntityV2();
          backupEntityV2.setVersion(localManagementSource.getVersion());
          backupEntityV2.setSourceId(member.name());
          backupEntityV2.setName(name);
          backupEntityV2.setStatus(status);
          if ("FAILED".equals(status)) {
            backupEntityV2.setError(localManagementSource.getBackupFailureReason(name));
          }
          localResult.add(backupEntityV2);
        }
        return localResult;
      }

      @Override
      public Collection<BackupEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("backups")
            .matrixParam("serverNames", member.name());

        return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), BackupEntityV2.class);
      }
    });
  }

  public Collection<LogEntityV2> getLogs(Set<String> serverNames, final Long sinceWhen) throws ServiceExecutionException {
    return forEachServer("getLogs", serverNames, new ForEachServer<LogEntityV2>() {
      @Override
      public Collection<LogEntityV2> queryLocalServer(L2Info member) {
        Collection<LogEntityV2> localResult = new ArrayList<LogEntityV2>();
        try {
          Collection<Notification> logNotifications = localManagementSource.getNotifications(sinceWhen);
          for (Notification logNotification : logNotifications) {
            LogEntityV2 logEntityV2 = new LogEntityV2();
            logEntityV2.setSourceId(member.name());
            logEntityV2.setVersion(localManagementSource.getVersion());
            logEntityV2.setMessage(logNotification.getMessage());
            logEntityV2.setTimestamp(logNotification.getTimeStamp());
            logEntityV2.setThrowableStringRep((String[])logNotification.getUserData());

            localResult.add(logEntityV2);
          }
        } catch (Exception e) {
          LogEntityV2 logEntityV2 = new LogEntityV2();
          logEntityV2.setSourceId(member.name());
          logEntityV2.setVersion(localManagementSource.getVersion());
          logEntityV2.setMessage(e.getMessage());

          localResult.add(logEntityV2);
        }
        return localResult;
      }

      @Override
      public Collection<LogEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("logs")
            .matrixParam("names", member.name());
        if (sinceWhen != null) { uriBuilder.matrixParam("sinceWhen", sinceWhen); }

        return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), LogEntityV2.class);
      }
    });
  }

  public Collection<OperatorEventEntityV2> getOperatorEvents(Set<String> serverNames, final Long sinceWhen, final Set<String> acceptableTypes, final boolean read) throws ServiceExecutionException {
    return forEachServer("getOperatorEvents", serverNames, new ForEachServer<OperatorEventEntityV2>() {
      @Override
      public Collection<OperatorEventEntityV2> queryLocalServer(L2Info member) {
        Collection<OperatorEventEntityV2> localResult = new ArrayList<OperatorEventEntityV2>();
        try {
          Collection<TerracottaOperatorEvent> operatorEvents = localManagementSource.getOperatorEvents(sinceWhen);
          for (TerracottaOperatorEvent operatorEvent : operatorEvents) {
            if (operatorEvent.isRead() && read) {
              // filter out read events
              continue;
            }
            if (acceptableTypes != null) {
              // filter out event types
              if (!acceptableTypes.contains(operatorEvent.getEventLevelAsString())) {
                continue;
              }
            }

            OperatorEventEntityV2 operatorEventEntityV2 = new OperatorEventEntityV2();
            operatorEventEntityV2.setSourceId(member.name());
            operatorEventEntityV2.setVersion(localManagementSource.getVersion());
            operatorEventEntityV2.setMessage(operatorEvent.getEventMessage());
            operatorEventEntityV2.setTimestamp(operatorEvent.getEventTime().getTime());
            operatorEventEntityV2.setCollapseString(operatorEvent.getCollapseString());
            operatorEventEntityV2.setEventSubsystem(operatorEvent.getEventSubsystemAsString());
            operatorEventEntityV2.setEventLevel(operatorEvent.getEventLevelAsString());
            operatorEventEntityV2.setRead(operatorEvent.isRead());

            localResult.add(operatorEventEntityV2);
          }
        } catch (Exception e) {
          OperatorEventEntityV2 operatorEventEntityV2 = new OperatorEventEntityV2();
          operatorEventEntityV2.setSourceId(member.name());
          operatorEventEntityV2.setVersion(localManagementSource.getVersion());
          operatorEventEntityV2.setMessage(e.getMessage());

          localResult.add(operatorEventEntityV2);
        }
        return localResult;
      }

      @Override
      public Collection<OperatorEventEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("operatorEvents")
            .matrixParam("names", member.name());
        if (sinceWhen != null) { uriBuilder.queryParam("sinceWhen", sinceWhen); }
        if (acceptableTypes != null) { uriBuilder.queryParam("eventTypes", toCsv(acceptableTypes)); }
        uriBuilder.queryParam("filterOutRead", read);

        return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), OperatorEventEntityV2.class);
      }
    });
  }

  public Collection<MBeanEntityV2> queryMBeans(Set<String> serverNames, final String query) throws ServiceExecutionException {
    return forEachServer("queryMBeans", serverNames, new ForEachServer<MBeanEntityV2>() {
      @Override
      public Collection<MBeanEntityV2> queryLocalServer(L2Info member) {
        Collection<MBeanEntityV2> localResult = new ArrayList<MBeanEntityV2>();
        try {
          Set<ObjectName> objectNames = localManagementSource.queryNames(query);
          for (ObjectName objectName : objectNames) {
            List<MBeanEntityV2.AttributeEntityV2> attributeEntities = new ArrayList<MBeanEntityV2.AttributeEntityV2>();
            Map<String, String> attributes = localManagementSource.getMBeanAttributeInfo(objectName);
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
              MBeanEntityV2.AttributeEntityV2 attributeEntityV2 = new MBeanEntityV2.AttributeEntityV2();
              attributeEntityV2.setName(entry.getKey());
              attributeEntityV2.setType(entry.getValue());
              attributeEntities.add(attributeEntityV2);
            }

            MBeanEntityV2 mBeanEntityV2 = new MBeanEntityV2();
            mBeanEntityV2.setSourceId(member.name());
            mBeanEntityV2.setVersion(localManagementSource.getVersion());
            mBeanEntityV2.setObjectName(objectName.toString());
            mBeanEntityV2.setAttributes(attributeEntities.toArray(new MBeanEntityV2.AttributeEntityV2[attributeEntities.size()]));

            localResult.add(mBeanEntityV2);
          }
        } catch (Exception e) {
          // ignore error when an MBean cannot be introspected
        }
        return localResult;
      }

      @Override
      public Collection<MBeanEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("jmx")
            .matrixParam("names", member.name());
        if (query != null) { uriBuilder.queryParam("q", query); }

        return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), MBeanEntityV2.class);
      }
    });
  }

  // this method duplicates the logic in forEachServer()
  public Collection<ServerGroupEntityV2> getServerGroups(Set<String> serverNames) throws ServiceExecutionException {
    Collection<ServerGroupEntityV2> localServerGroupEntities = new ArrayList<ServerGroupEntityV2>();
    Map<String, Future<Collection<ServerGroupEntityV2>>> futures = new HashMap<String, Future<Collection<ServerGroupEntityV2>>>();

    ServerGroupInfo[] serverGroupInfos = localManagementSource.getServerGroupInfos();
    for (final ServerGroupInfo serverGroupInfo : serverGroupInfos) {
      ServerGroupEntityV2 serverGroupEntityV2 = new ServerGroupEntityV2();

      serverGroupEntityV2.setVersion(localManagementSource.getVersion());
      serverGroupEntityV2.setName(serverGroupInfo.name());
      serverGroupEntityV2.setId(serverGroupInfo.id());

      L2Info[] members = serverGroupInfo.members();
      for (final L2Info member : members) {
        if (serverNames != null && !serverNames.contains(member.name())) { continue; }

        if (member.name().equals(localManagementSource.getLocalServerName())) {
          getServerGroups_local(localServerGroupEntities, serverGroupEntityV2, member);
        } else {
          getServerGroups_remote(futures, serverGroupInfo, member);
        }
      }
    }

    try {
      Map<String, ServerGroupEntityV2> mergedResult = new HashMap<String, ServerGroupEntityV2>();

      Collection<ServerGroupEntityV2> remoteServerGroupEntities = remoteManagementSource.collectEntitiesCollectionFromFutures(futures, timeoutService
          .getCallTimeout(), "getServerGroups", Integer.MAX_VALUE);
      for (ServerGroupEntityV2 serverGroupEntityV2 : remoteManagementSource.merge(localServerGroupEntities, remoteServerGroupEntities)) {
        ServerGroupEntityV2 existingSge = mergedResult.get(serverGroupEntityV2.getName());
        if (existingSge == null) {
          mergedResult.put(serverGroupEntityV2.getName(), serverGroupEntityV2);
        } else {
          existingSge.getServers().addAll(serverGroupEntityV2.getServers());
        }
      }

      return mergedResult.values();
    } catch (Exception e) {
      throw new ServiceExecutionException("error executing remote getServerGroups", e);
    }
  }

  private void getServerGroups_remote(Map<String, Future<Collection<ServerGroupEntityV2>>> futures, final ServerGroupInfo serverGroupInfo, final L2Info member) {
    final SecurityContextService.SecurityContext context = securityContextService.getSecurityContext();
    Future<Collection<ServerGroupEntityV2>> future = executorService.submit(new Callable<Collection<ServerGroupEntityV2>>() {
      @Override
      public Collection<ServerGroupEntityV2> call() throws Exception {
        securityContextService.setSecurityContext(context);
        try {
          UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
              .path("agents")
              .path("topologies")
              .path("servers")
              .matrixParam("names", member.name());

          try {
            Collection<TopologyEntityV2> topologyEntities = remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), TopologyEntityV2.class);
            return Collections.singleton(findServerGroupEntityV2ContainingServerWithName(topologyEntities, member.name()));
          } catch (ProcessingException che) {
            ServerGroupEntityV2 sgEntityV2 = new ServerGroupEntityV2();
            sgEntityV2.setVersion(localManagementSource.getVersion());
            sgEntityV2.setName(serverGroupInfo.name());
            sgEntityV2.setId(serverGroupInfo.id());

            ServerEntityV2 sEntityV2 = new ServerEntityV2();
            sEntityV2.setVersion(localManagementSource.getVersion());
            sEntityV2.getAttributes().put("Name", member.name());
            sEntityV2.getAttributes().put("Host", member.host());
            sEntityV2.getAttributes().put("JmxPort", member.jmxPort());
            sEntityV2.getAttributes().put("HostAddress", member.safeGetHostAddress());

            sgEntityV2.getServers().add(sEntityV2);
            return Collections.singleton(sgEntityV2);
          }
        } finally {
          securityContextService.clearSecurityContext();
        }
      }
    });
    futures.put(member.name(), future);
  }

  private void getServerGroups_local(Collection<ServerGroupEntityV2> localServerGroupEntities, ServerGroupEntityV2 serverGroupEntityV2, L2Info member) {
    ServerEntityV2 serverEntityV2;
    serverEntityV2 = new ServerEntityV2();
    serverEntityV2.setVersion(localManagementSource.getVersion());
    serverEntityV2.getAttributes().put("Name", member.name());
    serverEntityV2.getAttributes().put("Host", member.host());
    serverEntityV2.getAttributes().put("JmxPort", member.jmxPort());
    serverEntityV2.getAttributes().put("HostAddress", member.safeGetHostAddress());
    serverEntityV2.getAttributes().putAll(localManagementSource.getServerAttributes(SERVER_ENTITY_ATTRIBUTE_NAMES));

    serverGroupEntityV2.getServers().add(serverEntityV2);
    localServerGroupEntities.add(serverGroupEntityV2);
  }

  private static ServerGroupEntityV2 findServerGroupEntityV2ContainingServerWithName(Collection<TopologyEntityV2> topologyEntities, String name) {
    for (TopologyEntityV2 topologyEntityV2 : topologyEntities) {
      Set<ServerGroupEntityV2> serverGroupEntities = topologyEntityV2.getServerGroupEntities();
      for (ServerGroupEntityV2 serverGroupEntityV2 : serverGroupEntities) {
        Set<ServerEntityV2> servers = serverGroupEntityV2.getServers();
        for (ServerEntityV2 server : servers) {
          if (name.equals(server.getAttributes().get("Name"))) {
            return serverGroupEntityV2;
          }
        }
      }
    }
    return null;
  }

  public Map<String, Integer> getUnreadOperatorEventCount(Set<String> serverNames) throws ServiceExecutionException {
    Collection<TopologyEntityV2> topologyEntities = forEachServer("getUnreadOperatorEventCount", serverNames, new ForEachServer<TopologyEntityV2>() {
      @Override
      public Collection<TopologyEntityV2> queryLocalServer(L2Info member) {
        TopologyEntityV2 topologyEntityV2 = new TopologyEntityV2();
        topologyEntityV2.setVersion(localManagementSource.getVersion());
        topologyEntityV2.setUnreadOperatorEventCount(localManagementSource.getUnreadOperatorEventCount());
        return Collections.singleton(topologyEntityV2);
      }

      @Override
      public Collection<TopologyEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("topologies")
            .path("unreadOperatorEventCount")
            .matrixParam("serverNames", member.name());

        return remoteManagementSource.getFromRemoteL2(member.name(), uriBuilder.build(), TopologyEntityV2.class);
      }
    });

    Map<String, Integer> result = new HashMap<String, Integer>();
    for (TopologyEntityV2 topologyEntityV2 : topologyEntities) {
      Map<String, Integer> unreadOperatorEventCount = topologyEntityV2.getUnreadOperatorEventCount();

      for (Map.Entry<String, Integer> entry : unreadOperatorEventCount.entrySet()) {
        String key = entry.getKey();
        Integer value = entry.getValue();

        Integer totalValue = result.get(key);
        if (totalValue == null) { totalValue = 0; }
        totalValue += value;
        result.put(key, totalValue);
      }
    }
    return result;
  }

  public void runDgc(Set<String> serverNames) throws ServiceExecutionException {
    forEachServer("runDgc", serverNames, new ForEachServer<Representable>() {
      @Override
      public Collection<Representable> queryLocalServer(L2Info member) {
        if (!localManagementSource.isActiveCoordinator()) {
          return null;
        }

        localManagementSource.runDgc();
        return null;
      }

      @Override
      public Collection<Representable> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("diagnostics")
            .path("dgc")
            .matrixParam("serverNames", member.name());

        remoteManagementSource.postToRemoteL2(member.name(), uriBuilder.build());
        return null;
      }
    });
  }

  public void dumpClusterState(Set<String> serverNames) throws ServiceExecutionException {
    forEachServer("dumpClusterState", serverNames, new ForEachServer<Representable>() {
      @Override
      public Collection<Representable> queryLocalServer(L2Info member) {
        localManagementSource.dumpClusterState();
        return null;
      }

      @Override
      public Collection<Representable> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("diagnostics")
            .path("dumpClusterState")
            .matrixParam("serverNames", member.name());

        remoteManagementSource.postToRemoteL2(member.name(), uriBuilder.build());
        return null;
      }
    });
  }

  public Collection<BackupEntityV2> backup(Set<String> serverNames, String givenBackupName) throws ServiceExecutionException {
    final String backupName = givenBackupName != null ?
        givenBackupName :
        "backup." + new SimpleDateFormat("yyyyMMdd.HHmmss").format(new Date());

    return forEachServer("backup", serverNames, new ForEachServer<BackupEntityV2>() {
      @Override
      public Collection<BackupEntityV2> queryLocalServer(L2Info member) {
        if (!localManagementSource.isActiveCoordinator()) {
          return null;
        }

        BackupEntityV2 backupEntityV2 = new BackupEntityV2();
        backupEntityV2.setVersion(localManagementSource.getVersion());
        backupEntityV2.setSourceId(member.name());
        backupEntityV2.setName(backupName);

        try {
          localManagementSource.backup(backupName);
          backupEntityV2.setStatus(localManagementSource.getBackupStatus(backupName));
        } catch (Exception e) {
          backupEntityV2.setStatus(BackupManager.BackupStatus.FAILED.name());
          backupEntityV2.setError(e.getMessage());
        }

        return Collections.singleton(backupEntityV2);
      }

      @Override
      public Collection<BackupEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("backups")
            .matrixParam("serverNames", member.name())
            .queryParam("name", backupName);

        return remoteManagementSource.postToRemoteL2(member.name(), uriBuilder.build(), BackupEntityV2.class);
      }
    });
  }

  public void shutdownServers(Set<String> serverNames) throws ServiceExecutionException {
    final AtomicBoolean includeLocalServer = new AtomicBoolean(false);

    forEachServer("shutdownServers", serverNames, new ForEachServer<Representable>() {
      @Override
      public Collection<Representable> queryLocalServer(L2Info member) {
        includeLocalServer.set(true);
        return null;
      }

      @Override
      public Collection<Representable> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("shutdown")
            .matrixParam("serverNames", member.name());

        remoteManagementSource.postToRemoteL2(member.name(), uriBuilder.build());
        return null;
      }
    });

    // the local server must always be the last one to shutdown
    if (includeLocalServer.get()) {
      localManagementSource.shutdownServer();
    }
  }

  public boolean markOperatorEvent(final OperatorEventEntityV2 operatorEventEntityV2, final boolean read) throws ServiceExecutionException {
    String sourceId = operatorEventEntityV2.getSourceId();
    if (sourceId.equals(localManagementSource.getLocalServerName())) {
      TerracottaOperatorEvent terracottaOperatorEvent = new TerracottaOperatorEventImpl(
                                                                                        TerracottaOperatorEvent.EventLevel.valueOf(operatorEventEntityV2.getEventLevel()),
                                                                                        TerracottaOperatorEvent.EventSubsystem.valueOf(operatorEventEntityV2.getEventSubsystem()),
                                                                                        TerracottaOperatorEvent.EventType.valueOf(operatorEventEntityV2.getEventType()),
                                                                                        operatorEventEntityV2.getMessage(), operatorEventEntityV2.getTimestamp(), operatorEventEntityV2.getCollapseString());

      return localManagementSource.markOperatorEvent(terracottaOperatorEvent, read);
    } else {
      UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
          .path("agents")
          .path("operatorEvents");
      uriBuilder = (read) ? uriBuilder.path("read") : uriBuilder.path("unread");

      return (Boolean)remoteManagementSource.postToRemoteL2(sourceId, uriBuilder.build(), Collections.singleton(operatorEventEntityV2));
    }
  }

  public Collection<TopologyReloadStatusEntityV2> reloadConfiguration(Set<String> serverNames) throws ServiceExecutionException {
    return forEachServer("reloadConfiguration", serverNames, new ForEachServer<TopologyReloadStatusEntityV2>() {
      @Override
      public Collection<TopologyReloadStatusEntityV2> queryLocalServer(L2Info member) {
        TopologyReloadStatusEntityV2 topologyReloadStatusEntityV2 = new TopologyReloadStatusEntityV2();
        topologyReloadStatusEntityV2.setSourceId(member.name());
        topologyReloadStatusEntityV2.setVersion(localManagementSource.getVersion());
        try {
          TopologyReloadStatus topologyReloadStatus = localManagementSource.reloadConfiguration();
          topologyReloadStatusEntityV2.setStatus(topologyReloadStatus.name());
        } catch (ManagementSourceException e) {
          topologyReloadStatusEntityV2.setStatus(e.getMessage());
        }
        return Collections.singleton(topologyReloadStatusEntityV2);
      }

      @Override
      public Collection<TopologyReloadStatusEntityV2> queryRemoteServer(L2Info member) throws Exception {
        UriBuilder uriBuilder = UriBuilder.fromPath("tc-management-api")
            .path("agents")
            .path("diagnostics")
            .path("reloadConfiguration")
            .matrixParam("serverNames", member.name());

        return remoteManagementSource.postToRemoteL2(member.name(), uriBuilder.build(), TopologyReloadStatusEntityV2.class);
      }
    });
  }

  interface ForEachServer<T> {
    Collection<T> queryLocalServer(L2Info member);
    Collection<T> queryRemoteServer(L2Info member) throws Exception;
  }

  private <T extends Representable> Collection<T> forEachServer(String methodName, Set<String> serverNames, final ForEachServer<T> fes) throws ServiceExecutionException {
    return forEachServer(methodName, serverNames, Integer.MAX_VALUE, fes);
  }

  // the logic of this method has been duplicated in getServerGroups()
  private <T extends Representable> Collection<T> forEachServer(String methodName, Set<String> serverNames, int maxEntries, final ForEachServer<T> fes) throws ServiceExecutionException {
    Collection<T> localResult = new ArrayList<T>();
    Map<String, Future<Collection<T>>> futures = new HashMap<String, Future<Collection<T>>>();

    L2Info[] members = localManagementSource.getL2Infos();
    for (final L2Info member : members) {
      if (serverNames != null && !serverNames.contains(member.name())) { continue; }

      if (member.name().equals(localManagementSource.getLocalServerName())) {
        Collection<T> c = fes.queryLocalServer(member);
        if (c != null) {
          localResult.addAll(c);
        }
      } else {
        final SecurityContextService.SecurityContext context = securityContextService.getSecurityContext();
        Future<Collection<T>> future = executorService.submit(new Callable<Collection<T>>() {
          @Override
          public Collection<T> call() throws Exception {
            securityContextService.setSecurityContext(context);
            try {
              return fes.queryRemoteServer(member);
            } finally {
              securityContextService.clearSecurityContext();
            }
          }
        });
        futures.put(member.name(), future);
      }
    }

    try {
      return remoteManagementSource.merge(localResult, remoteManagementSource.collectEntitiesCollectionFromFutures(futures, timeoutService
          .getCallTimeout(), methodName, maxEntries));
    } catch (Exception e) {
      remoteManagementSource.cancelFutures(futures.values());
      throw new ServiceExecutionException("error executing remote " + methodName, e);
    }
  }

}