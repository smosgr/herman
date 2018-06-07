/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.libertymutualgroup.herman.aws.ecs.loadbalancing;

import com.amazonaws.services.cloudformation.model.Tag;
import com.amazonaws.services.ecs.model.ContainerDefinition;
import com.amazonaws.services.ecs.model.LoadBalancer;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.model.AddTagsRequest;
import com.amazonaws.services.elasticloadbalancing.model.ApplySecurityGroupsToLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.AttachLoadBalancerToSubnetsRequest;
import com.amazonaws.services.elasticloadbalancing.model.ConfigureHealthCheckRequest;
import com.amazonaws.services.elasticloadbalancing.model.CreateAppCookieStickinessPolicyRequest;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerListenersRequest;
import com.amazonaws.services.elasticloadbalancing.model.CreateLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.DeleteLoadBalancerListenersRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult;
import com.amazonaws.services.elasticloadbalancing.model.DuplicateLoadBalancerNameException;
import com.amazonaws.services.elasticloadbalancing.model.HealthCheck;
import com.amazonaws.services.elasticloadbalancing.model.Listener;
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription;
import com.amazonaws.services.elasticloadbalancing.model.SetLoadBalancerPoliciesOfListenerRequest;
import com.atlassian.bamboo.build.logger.BuildLogger;
import com.libertymutualgroup.herman.aws.ecs.EcsPortHandler;
import com.libertymutualgroup.herman.aws.ecs.EcsPushDefinition;
import com.libertymutualgroup.herman.aws.ecs.cluster.EcsClusterMetadata;
import com.libertymutualgroup.herman.task.common.CommonTaskProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EcsLoadBalancerHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(EcsLoadBalancerHandler.class);

    private static final String HTTPS = "HTTPS";
    private AmazonElasticLoadBalancing elbClient;
    private CertHandler certHandler;
    private BuildLogger buildLogger;
    private DnsRegistrar dnsRegistrar;
    private CommonTaskProperties taskProperties;

    public EcsLoadBalancerHandler(AmazonElasticLoadBalancing elbClient, CertHandler certHandler,
        DnsRegistrar dnsRegistrar, BuildLogger buildLogger, CommonTaskProperties taskProperties) {
        this.elbClient = elbClient;
        this.certHandler = certHandler;
        this.dnsRegistrar = dnsRegistrar;
        this.buildLogger = buildLogger;
        this.taskProperties = taskProperties;
    }

    public LoadBalancer createLoadBalancer(EcsClusterMetadata clusterMetadata, EcsPushDefinition definition) {

        String appName = definition.getAppName();

        EcsPortHandler portHandler = new EcsPortHandler();

        String protocol = definition.getService().getProtocol();
        if (protocol == null) {
            protocol = HTTPS;
        }

        String urlPrefix = appName;
        if (definition.getService().getUrlPrefixOverride() != null) {
            urlPrefix = definition.getService().getUrlPrefixOverride();
        }

        String urlSuffix = definition.getService().getUrlSuffix();
        DeriveCertResult deriveCertResult = certHandler.deriveCert(protocol, urlSuffix, urlPrefix);

        ContainerDefinition webContainer = portHandler.findContainerWithExposedPort(definition, false);
        Integer randomPort = webContainer.getPortMappings().get(0).getHostPort();
        Integer containerPort = webContainer.getPortMappings().get(0).getContainerPort();
        String containerName = webContainer.getName();

        boolean isInternetFacingUrlScheme = certHandler.isInternetFacingUrlScheme(deriveCertResult.getSslCertificate(),
            definition.getService().getUrlSchemeOverride());
        boolean isUsingInternalSubnets = true;
        String elbScheme;
        List<String> elbSubnets;
        if (isInternetFacingUrlScheme || "internet-facing".equals(definition.getService().getElbSchemeOverride())) {
            elbScheme = "internet-facing";
            isUsingInternalSubnets = false;
            elbSubnets = clusterMetadata.getPublicSubnets();
        } else {
            elbScheme = "internal";
            elbSubnets = clusterMetadata.getElbSubnets();
        }

        // Handle akamai
        List<String> elbSecurityGroups = new ArrayList<>();
        if (isInternetFacingUrlScheme && HTTPS.equals(protocol)) {
            elbSecurityGroups.addAll(clusterMetadata.getAkamaiSecurityGroup());
        } else {
            elbSecurityGroups.addAll(clusterMetadata.getElbSecurityGroups());
        }

        List<Listener> listeners = generateListeners(definition.getService().getElbSourcePorts(), randomPort, protocol,
            deriveCertResult.getCertArn());
        List<com.amazonaws.services.elasticloadbalancing.model.Tag> tags = getElbTagList(
            clusterMetadata.getClusterCftStackTags(), appName);
        CreateLoadBalancerRequest createLoadBalancerRequest = new CreateLoadBalancerRequest().withSubnets(elbSubnets)
            .withListeners(listeners).withScheme(elbScheme).withSecurityGroups(elbSecurityGroups)
            .withLoadBalancerName(appName).withTags(tags);
        try {
            elbClient.createLoadBalancer(createLoadBalancerRequest);
        } catch (DuplicateLoadBalancerNameException e) {
            LOGGER.debug("Error creating ELB: " + appName, e);
            buildLogger.addBuildLogEntry("Updating ELB: " + appName);
            elbClient.deleteLoadBalancerListeners(new DeleteLoadBalancerListenersRequest().withLoadBalancerName(appName)
                .withLoadBalancerPorts(CollectionUtils.isNotEmpty(definition.getService().getElbSourcePorts())
                    ? definition.getService().getElbSourcePorts()
                    : Arrays.asList(443)));
            elbClient.createLoadBalancerListeners(
                new CreateLoadBalancerListenersRequest().withListeners(listeners).withLoadBalancerName(appName));
            elbClient.applySecurityGroupsToLoadBalancer(new ApplySecurityGroupsToLoadBalancerRequest()
                .withLoadBalancerName(appName).withSecurityGroups(elbSecurityGroups));
            elbClient.addTags(new AddTagsRequest().withLoadBalancerNames(appName).withTags(tags));

            elbClient.attachLoadBalancerToSubnets(
                new AttachLoadBalancerToSubnetsRequest().withSubnets(elbSubnets).withLoadBalancerName(appName));
        } catch (Exception ex) {
            throw new RuntimeException("Error creating ELB: " + createLoadBalancerRequest);
        }

        if (definition.getService().getAppStickinessCookie() != null) {

            elbClient.createAppCookieStickinessPolicy(new CreateAppCookieStickinessPolicyRequest()
                .withLoadBalancerName(appName).withPolicyName("StickyElbPolicy")
                .withCookieName(definition.getService().getAppStickinessCookie()));
            elbClient.setLoadBalancerPoliciesOfListener(new SetLoadBalancerPoliciesOfListenerRequest()
                .withLoadBalancerName(appName).withLoadBalancerPort(443).withPolicyNames("StickyElbPolicy"));
        }

        HealthCheck healthCheck = definition.getService().getHealthCheck();
        String healthCheckPath = healthCheck.getTarget();
        if ("TCP".equals(healthCheckPath)) {
            healthCheckPath = "TCP:" + randomPort;
        } else {
            healthCheckPath = "HTTPS:" + randomPort + healthCheckPath;
        }
        healthCheck.setTarget(healthCheckPath);

        if (healthCheck.getInterval() == null) {
            healthCheck.setInterval(30);
        }
        if (healthCheck.getHealthyThreshold() == null) {
            healthCheck.setHealthyThreshold(2);
        }
        if (healthCheck.getTimeout() == null) {
            healthCheck.setTimeout(10);
        }
        if (healthCheck.getUnhealthyThreshold() == null) {
            healthCheck.setUnhealthyThreshold(10);
        }

        elbClient.configureHealthCheck(
            new ConfigureHealthCheckRequest().withLoadBalancerName(appName).withHealthCheck(healthCheck));

        DescribeLoadBalancersResult elbResult = elbClient
            .describeLoadBalancers(new DescribeLoadBalancersRequest().withLoadBalancerNames(appName));
        LoadBalancerDescription elbDesc = elbResult.getLoadBalancerDescriptions().get(0);

        String registeredUrl = urlPrefix + "." + definition.getService().getUrlSuffix();

        if (isUsingInternalSubnets) {
            dnsRegistrar.registerDns(registeredUrl, elbDesc.getDNSName(), appName,
                clusterMetadata.getClusterCftStackTags());
            buildLogger.addBuildLogEntry("... URL Registered: " + protocol.toLowerCase() + "://" + registeredUrl);
        } else {
            buildLogger.addBuildLogEntry(
                "... Raw ELB DNS for Akamai: " + protocol.toLowerCase() + "://" + elbDesc.getDNSName());
            buildLogger.addBuildLogEntry("... Expected Akamai url: " + protocol.toLowerCase() + "://" + registeredUrl);
        }

        buildLogger.addBuildLogEntry("... ELB updates complete");
        return new LoadBalancer().withContainerName(containerName).withContainerPort(containerPort)
            .withLoadBalancerName(appName);
    }

    private List<Listener> generateListeners(List<Integer> elbSourcePorts, Integer randomPort, String protocol,
        String cert) {
        List<Listener> listenerList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(elbSourcePorts)) {
            for (Integer elbSourceport : elbSourcePorts) {
                listenerList.add(generateListener(randomPort, protocol, cert, elbSourceport));
            }
        } else {
            listenerList.add(generateListener(randomPort, protocol, cert, 443));
        }

        return listenerList;
    }

    private Listener generateListener(Integer randomPort, String protocol, String cert, Integer elbPort) {
        return new Listener().withLoadBalancerPort(elbPort).withInstancePort(randomPort).withProtocol(protocol)
            .withSSLCertificateId(cert).withInstanceProtocol(protocol);
    }

    private List<com.amazonaws.services.elasticloadbalancing.model.Tag> getElbTagList(List<Tag> tags, String name) {
        List<com.amazonaws.services.elasticloadbalancing.model.Tag> result = new ArrayList<>();
        for (Tag cftTag : tags) {
            if ("Name".equals(cftTag.getKey())) {
                result.add(new com.amazonaws.services.elasticloadbalancing.model.Tag()
                    .withKey(this.taskProperties.getClusterTagKey())
                    .withValue(cftTag.getValue()));
                result.add(new com.amazonaws.services.elasticloadbalancing.model.Tag().withKey("Name").withValue(name));
            } else {
                result.add(new com.amazonaws.services.elasticloadbalancing.model.Tag().withKey(cftTag.getKey())
                    .withValue(cftTag.getValue()));
            }
        }
        return result;
    }

}