/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package org.ow2.proactive.connector.iaas.cloud.provider.jclouds.aws;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jclouds.aws.ec2.AWSEC2Api;
import org.jclouds.aws.ec2.compute.AWSEC2TemplateOptions;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.domain.internal.NodeMetadataImpl;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.domain.Location;
import org.jclouds.ec2.EC2Api;
import org.jclouds.ec2.domain.KeyPair;
import org.jclouds.ec2.domain.PublicIpInstanceIdPair;
import org.jclouds.ec2.features.ElasticIPAddressApi;
import org.jclouds.ec2.features.KeyPairApi;
import org.ow2.proactive.connector.iaas.cloud.TagManager;
import org.ow2.proactive.connector.iaas.cloud.provider.jclouds.JCloudsProvider;
import org.ow2.proactive.connector.iaas.model.Infrastructure;
import org.ow2.proactive.connector.iaas.model.Instance;
import org.ow2.proactive.connector.iaas.model.InstanceCredentials;
import org.ow2.proactive.connector.iaas.model.Options;
import org.ow2.proactive.connector.iaas.model.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Sets;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;


@Component
@Log4j2
public class AWSEC2JCloudsProvider extends JCloudsProvider {

    @Getter
    private final String type = "aws-ec2";

    private static final String INSTANCE_ID_REGION_SEPARATOR = "/";

    /**
     * This field stores the couple (AWS key pair name, private key) for each 
     * AWS region in which a key pair has already been generated by the 
     * connector-iaas. At maximum, the length of this map is the number of
     * regions in AWS.
     */
    private Map<String, SimpleImmutableEntry<String, String>> generatedKeyPairsPerAwsRegion = new HashMap<>();

    @Autowired
    private TagManager tagManager;

    @Override
    public Set<Instance> createInstance(Infrastructure infrastructure, Instance instance) {

        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);

        TemplateBuilder templateBuilder = computeService.templateBuilder()
                                                        .minRam(Integer.parseInt(instance.getHardware().getMinRam()))
                                                        .minCores(Double.parseDouble(instance.getHardware()
                                                                                             .getMinCores()))
                                                        .imageId(instance.getImage());

        Template template = templateBuilder.build();

        Optional.ofNullable(instance.getOptions()).ifPresent(options -> addOptions(template, options));

        // Add tags
        addTags(template, tagManager.retrieveAllTags(instance.getOptions()));

        addCredential(template,
                      Optional.ofNullable(instance.getCredentials())
                              .orElseGet(() -> createCredentialsIfNotExist(infrastructure, instance)));

        Set<? extends NodeMetadata> createdNodeMetaData = Sets.newHashSet();

        try {
            createdNodeMetaData = computeService.createNodesInGroup(instance.getTag(),
                                                                    Integer.parseInt(instance.getNumber()),
                                                                    template);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return createdNodeMetaData.stream()
                                  .map(computeMetadata -> (NodeMetadataImpl) computeMetadata)
                                  .map(this::createInstanceFromNode)
                                  .collect(Collectors.toSet());

    }

    private InstanceCredentials createCredentialsIfNotExist(Infrastructure infrastructure, Instance instance) {
        String regionOfCredentials = getRegionFromImage(instance);

        // we keep in memory a default key pair to use in each of the region
        // where EC2 instances are deployed using default credentials
        if (!generatedKeyPairsPerAwsRegion.containsKey(regionOfCredentials)) {
            SimpleImmutableEntry<String, String> keyPair = createKeyPair(infrastructure, instance);
            generatedKeyPairsPerAwsRegion.put(regionOfCredentials, keyPair);
        } else {
            // we have a key pair entry in memory, but we are going to check
            // in addition whether this key exists in AWS
            KeyPairApi keyPairApi = getKeyPairApi(infrastructure);
            String inMemoryKeyPairName = generatedKeyPairsPerAwsRegion.get(regionOfCredentials).getKey();
            Set<KeyPair> awsKeyPairsInRegionWithName = keyPairApi.describeKeyPairsInRegion(regionOfCredentials,
                                                                                           inMemoryKeyPairName);
            // if we have the key in memory but not in AWS, we need to create
            // one in AWS and replace it in memory
            if (awsKeyPairsInRegionWithName.stream()
                                           .noneMatch(keyPair -> keyPair.getKeyName().equals(inMemoryKeyPairName))) {
                SimpleImmutableEntry<String, String> keyPair = createKeyPair(infrastructure, instance);
                generatedKeyPairsPerAwsRegion.put(regionOfCredentials, keyPair);
            }
        }

        // we are now sure that we have a key pair both in memory and in AWS
        // for the given region: so retrieve it and use it to create default
        // credentials
        String keyPairName = generatedKeyPairsPerAwsRegion.get(regionOfCredentials).getKey();
        return new InstanceCredentials(getVmUserLogin(), null, keyPairName, null, null);
    }

    private void addCredential(Template template, InstanceCredentials credentials) {
        log.info("Username given for instance creation: " + credentials.getUsername());
        Optional.ofNullable(credentials.getUsername())
                .filter(StringUtils::isNotEmpty)
                .filter(StringUtils::isNotBlank)
                .ifPresent(username -> template.getOptions()
                                               .as(AWSEC2TemplateOptions.class)
                                               .overrideLoginUser(credentials.getUsername()));

        log.info("Public key name given for instance creation: " + credentials.getPublicKeyName());
        Optional.ofNullable(credentials.getPublicKeyName())
                .filter(keyName -> !keyName.isEmpty())
                .ifPresent(keyName -> template.getOptions()
                                              .as(AWSEC2TemplateOptions.class)
                                              .keyPair(credentials.getPublicKeyName()));
    }

    private void addOptions(Template template, Options options) {
        Optional.ofNullable(options.getSpotPrice())
                .filter(spotPrice -> !spotPrice.isEmpty())
                .ifPresent(spotPrice -> template.getOptions()
                                                .as(AWSEC2TemplateOptions.class)
                                                .spotPrice(Float.valueOf(options.getSpotPrice())));

        Optional.ofNullable(options.getSecurityGroupNames())
                .filter(securityGroupName -> !securityGroupName.isEmpty())
                .ifPresent(securityGroupName -> template.getOptions()
                                                        .as(AWSEC2TemplateOptions.class)
                                                        .securityGroupIds(options.getSecurityGroupNames()));

        Optional.ofNullable(options.getSubnetId())
                .filter(subnetId -> !subnetId.isEmpty())
                .ifPresent(subnetId -> template.getOptions()
                                               .as(AWSEC2TemplateOptions.class)
                                               .subnetId(options.getSubnetId()));

    }

    private void addTags(Template template, List<Tag> tags) {
        template.getOptions()
                .as(AWSEC2TemplateOptions.class)
                .userMetadata(tags.stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
    }

    private String getRegionFromNode(ComputeService computeService, NodeMetadata node) {
        Location nodeLocation = node.getLocation();
        Set<? extends Location> assignableLocations = computeService.listAssignableLocations();
        while (!assignableLocations.contains(nodeLocation)) {
            nodeLocation = nodeLocation.getParent();
        }
        return nodeLocation.getId();
    }

    @Override
    public String addToInstancePublicIp(Infrastructure infrastructure, String instanceId, String optionalDesiredIp) {

        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        NodeMetadata node = computeService.getNodeMetadata(instanceId);
        ElasticIPAddressApi elasticIPAddressApi = computeService.getContext()
                                                                .unwrapApi(AWSEC2Api.class)
                                                                .getElasticIPAddressApi()
                                                                .get();

        // Get the region
        String region;
        if (instanceId.contains(INSTANCE_ID_REGION_SEPARATOR)) {
            region = instanceId.split(INSTANCE_ID_REGION_SEPARATOR)[0];
        } else {
            region = getRegionFromNode(computeService, node);
        }

        String id = node.getProviderId();

        // Try to assign existing IP
        if (Optional.ofNullable(optionalDesiredIp).isPresent()) {
            elasticIPAddressApi.associateAddressInRegion(region, optionalDesiredIp, id);
            return optionalDesiredIp;
        }

        // Try to associate to an existing IP
        String ip = null;
        Set<PublicIpInstanceIdPair> unassignedIps = elasticIPAddressApi.describeAddressesInRegion(region)
                                                                       .stream()
                                                                       .filter(address -> address.getInstanceId() == null)
                                                                       .collect(Collectors.toSet());
        boolean associated;
        for (PublicIpInstanceIdPair unassignedIp : unassignedIps) {
            associated = false;
            try {
                elasticIPAddressApi.associateAddressInRegion(region, unassignedIp.getPublicIp(), id);
                associated = true;
            } catch (RuntimeException e) {
                log.warn("Cannot associate address " + unassignedIp.getPublicIp() + " in region " + region, e);
            }
            if (associated) {
                ip = unassignedIp.getPublicIp();
                break;
            }
        }
        // Allocate a new IP otherwise
        if (ip == null) {
            try {
                ip = elasticIPAddressApi.allocateAddressInRegion(region);
            } catch (Exception e) {
                throw new RuntimeException("Failed to allocate a new IP address. All IP addresses are in use.", e);
            }
            elasticIPAddressApi.associateAddressInRegion(region, ip, id);
        }
        return ip;
    }

    @Override
    public void removeInstancePublicIp(Infrastructure infrastructure, String instanceId, String optionalDesiredIp) {

        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        NodeMetadata node = computeService.getNodeMetadata(instanceId);
        String region = node.getLocation().getId();
        ElasticIPAddressApi elasticIPAddressApi = computeService.getContext()
                                                                .unwrapApi(AWSEC2Api.class)
                                                                .getElasticIPAddressApi()
                                                                .get();
        // Try to dissociate the specified IP
        if (Optional.ofNullable(optionalDesiredIp).isPresent()) {
            elasticIPAddressApi.disassociateAddressInRegion(region, optionalDesiredIp);
            return;
        }
        // Dissociate one of the IP associated to the instance
        node.getPublicAddresses().stream().findAny().ifPresent(ip -> {
            elasticIPAddressApi.disassociateAddressInRegion(region, ip);
        });
    }

    @Override
    public SimpleImmutableEntry<String, String> createKeyPair(Infrastructure infrastructure, Instance instance) {
        KeyPairApi keyPairApi = getKeyPairApi(infrastructure);
        String region = getRegionFromImage(instance);
        String keyPairName = "default-" + region + "-" + UUID.randomUUID();
        try {
            KeyPair keyPair = keyPairApi.createKeyPairInRegion(region, keyPairName);
            log.info("Created key pair '" + keyPairName + "' in region '" + region + "'");
            return new SimpleImmutableEntry<>(keyPairName, keyPair.getKeyMaterial());
        } catch (RuntimeException e) {
            log.warn("Cannot create key pair in region '" + region, e);
            return null;
        }
    }

    private KeyPairApi getKeyPairApi(Infrastructure infrastructure) {
        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        EC2Api ec2Api = computeService.getContext().unwrapApi(EC2Api.class);
        if (ec2Api.getKeyPairApi().isPresent()) {
            return ec2Api.getKeyPairApi().get();
        } else {
            throw new UnsupportedOperationException("Cannot retrieve AWS key pair API, which enables key pair creation");
        }
    }

    private String getRegionFromImage(Instance instance) {
        String image = instance.getImage();
        return image.split(INSTANCE_ID_REGION_SEPARATOR)[0];
    }

    @Override
    public RunScriptOptions getRunScriptOptionsWithCredentials(InstanceCredentials credentials) {
        // retrieve the passed username or read it from the property file
        String username = Optional.ofNullable(credentials.getUsername())
                                  .filter(StringUtils::isNotEmpty)
                                  .filter(StringUtils::isNotBlank)
                                  .orElse(getVmUserLogin());
        log.info("Credentials used to execute script on instance: [username=" + username + ", privateKey=" +
                 credentials.getPrivateKey() + "]");
        // Currently in AWS EC2 root login is forbidden, as well as
        // username/password login. So the only way to login to run the script
        // is by giving username/private key credentials
        return RunScriptOptions.Builder.runAsRoot(false)
                                       .overrideLoginUser(username)
                                       .overrideLoginPrivateKey(credentials.getPrivateKey());
    }

    @Override
    protected RunScriptOptions getDefaultRunScriptOptionsUsingInstanceId(String instanceId,
            Infrastructure infrastructure) {
        ComputeService computeService = getComputeServiceFromInfastructure(infrastructure);
        NodeMetadata node = computeService.getNodeMetadata(instanceId);

        String subdividedRegion = getRegionFromNode(computeService, node);
        String keyPairRegion = extractRegionFromSubdividedRegion(subdividedRegion);

        return buildDefaultRunScriptOptions(keyPairRegion);
    }

    @Override
    protected RunScriptOptions getDefaultRunScriptOptionsUsingInstanceTag(String instanceTag,
            Infrastructure infrastructure) {
        // retrieve at least one instance that has the given tag to know in
        // which AWS region we are
        Instance taggedInstance = getCreatedInfrastructureInstances(infrastructure).stream()
                                                                                   .filter(instance -> instance.getTag()
                                                                                                               .equals(instanceTag))
                                                                                   .findAny()
                                                                                   .orElseThrow(() -> new IllegalArgumentException("Unable to create script options: cannot retrieve instance id from tag " +
                                                                                                                                   instanceTag));
        String subdividedRegion = getRegionFromImage(taggedInstance);
        String keyPairRegion = extractRegionFromSubdividedRegion(subdividedRegion);

        return buildDefaultRunScriptOptions(keyPairRegion);
    }

    private RunScriptOptions buildDefaultRunScriptOptions(String keyPairRegion) {
        SimpleImmutableEntry<String, String> defaultKeyPairInRegion = generatedKeyPairsPerAwsRegion.get(keyPairRegion);
        Optional.ofNullable(defaultKeyPairInRegion)
                .ifPresent(keyPair -> log.info("Default script options: username=" + getVmUserLogin() +
                                               ", privateKey=" + keyPair.getValue()));
        return Optional.ofNullable(defaultKeyPairInRegion)
                       .map(keyPair -> RunScriptOptions.Builder.runAsRoot(false)
                                                               .overrideLoginUser(getVmUserLogin())
                                                               .overrideLoginPrivateKey(keyPair.getValue()))
                       .orElse(RunScriptOptions.NONE);
    }

    private String extractRegionFromSubdividedRegion(String subdividedRegion) {
        // the region returned here contains a subdivision of the region, for
        // example eu-west-1c for the region eu-west-1, so we need to remove
        // the subdivision to have the exact region name of the key
        String region = subdividedRegion.substring(0, subdividedRegion.length() - 1);
        log.debug("Subdivided region=" + subdividedRegion + ", extracted region=" + region);
        return region;
    }

}
