/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.config;


import com.tc.classloader.ServiceLocator;
import com.tc.productinfo.ProductInfo;
import com.tc.properties.TCPropertiesImpl;
import org.terracotta.configuration.Configuration;
import org.terracotta.configuration.ConfigurationProvider;
import org.terracotta.configuration.ServerConfiguration;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.terracotta.configuration.ConfigurationException;
import com.tc.text.PrettyPrintable;
import java.util.List;

public class ServerConfigurationManager implements PrettyPrintable {

  private final ConfigurationProvider configurationProvider;
  private final ServiceLocator serviceLocator;
  private final List<String> startUpArgs;
  private final ProductInfo productInfo;

  private Configuration configuration;
  private ServerConfiguration serverConfiguration;

  public ServerConfigurationManager(ConfigurationProvider configurationProvider,
                                    ServiceLocator classLoader,
                                    List<String> startUpArgs) {
    Objects.requireNonNull(configurationProvider);
    Objects.requireNonNull(classLoader);
    Objects.requireNonNull(startUpArgs);

    this.configurationProvider = configurationProvider;
    this.serviceLocator = classLoader;
    this.startUpArgs = startUpArgs;
    this.productInfo = generateProductInfo(serviceLocator);
  }

  private ProductInfo generateProductInfo(ServiceLocator locator) {
    return ProductInfo.getInstance(locator.createUniversalClassLoader());
  }

  public ProductInfo getProductInfo() {
    return productInfo;
  }

  public void initialize() throws ConfigurationException {
    this.configurationProvider.initialize(this.startUpArgs);
    
    this.configuration = configurationProvider.getConfiguration();
    if (this.configuration == null) {
      throw new ConfigurationException("unable to determine server configuration");
    }

    this.serverConfiguration = this.configuration.getServerConfiguration();
    if (this.serverConfiguration == null) {
      throw new ConfigurationException("unable to determine server configuration");
    }
    processTcProperties(configuration.getTcProperties());
  }

  public void close() {
    configurationProvider.close();
  }

  public String[] getProcessArguments() {
    return startUpArgs.toArray(new String[startUpArgs.size()]);
  }

  public ServerConfiguration getServerConfiguration() {
    return this.serverConfiguration;
  }

  public GroupConfiguration getGroupConfiguration() {
    Map<String, ServerConfiguration> serverConfigurationMap = getServerConfigurationMap(configuration.getServerConfigurations());

    return new GroupConfiguration(serverConfigurationMap, this.serverConfiguration.getName());
  }
  
  public InputStream rawConfigFile() {
    String text = configuration.getRawConfiguration();
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  public String rawConfigString() {
    return configuration.getRawConfiguration();
  }

  public String[] allCurrentlyKnownServers() {
    return getGroupConfiguration().getMembers();
  }

  public boolean isPartialConfiguration() {
    return this.configuration.isPartialConfiguration();
  }

  public ServiceLocator getServiceLocator() {
    return this.serviceLocator;
  }

  public Configuration getConfiguration() {
    return configuration;
  }

  public ConfigurationProvider getConfigurationProvider() {
    return configurationProvider;
  }

  private Map<String, ServerConfiguration> getServerConfigurationMap(Collection<ServerConfiguration> servers) {
    Map<String, ServerConfiguration> serverConfigurationMap = new HashMap<>();
    for (ServerConfiguration server : servers) {
      if (server.getName() != null) {
        serverConfigurationMap.put(server.getName(), server);
      }
    }
    return serverConfigurationMap;
  }

  private static void processTcProperties(Properties tcProperties) {
    Map<String, String> propMap = new HashMap<>();

    if (tcProperties != null) {
      tcProperties.forEach((k, v)->propMap.put(k.toString().trim(), v.toString().trim()));
    }

    TCPropertiesImpl.getProperties().overwriteTcPropertiesFromConfig(propMap);
  }

  @Override
  public Map<String, ?> getStateMap() {
    if (configuration instanceof PrettyPrintable) {
      return ((PrettyPrintable)configuration).getStateMap();
    } else {
      return Collections.emptyMap();
    }
  }
}
