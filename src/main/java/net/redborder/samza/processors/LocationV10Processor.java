package net.redborder.samza.processors;

import net.redborder.samza.enrichments.EnrichManager;
import net.redborder.samza.store.StoreManager;
import net.redborder.samza.util.constants.Constants;
import net.redborder.samza.util.constants.Dimension;
import org.apache.samza.config.Config;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.apache.samza.task.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static net.redborder.samza.util.constants.Dimension.*;

public class LocationV10Processor extends Processor<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(LocationV10Processor.class);
    private static final SystemStream OUTPUT_STREAM = new SystemStream("kafka", Constants.ENRICHMENT_LOC_OUTPUT_TOPIC);
    final public static String LOCATION_STORE = "location";
    private static final String DATASOURCE = "rb_location";

    private final List<String> dimToDruid = Arrays.asList(MARKET, MARKET_UUID, ORGANIZATION, ORGANIZATION_UUID,
            DEPLOYMENT, DEPLOYMENT_UUID, SENSOR_NAME, SENSOR_UUID, NAMESPACE, SERVICE_PROVIDER, SERVICE_PROVIDER_UUID);

    private KeyValueStore<String, Map<String, Object>> store;
    private KeyValueStore<String, Long> countersStore;
    private KeyValueStore<String, Long> flowsNumber;
    private Map<Integer, String> cache;

    public LocationV10Processor(StoreManager storeManager, EnrichManager enrichManager, Config config, TaskContext context) {
        super(storeManager, enrichManager, config, context);
        store = storeManager.getStore(LOCATION_STORE);
        countersStore = (KeyValueStore<String, Long>) context.getStore("counter");
        flowsNumber = (KeyValueStore<String, Long>) context.getStore("flows-number");

        cache = new HashMap<>();
        cache.put(0, "IDLE");
        cache.put(1, "AAA_PENDING");
        cache.put(2, "AUTHENTICATED");
        cache.put(3, "ASSOCIATED");
        cache.put(4, "POWERSAVE");
        cache.put(5, "DISASSOCIATED");
        cache.put(6, "TO_BE_DELETED");
        cache.put(7, "PROBING");
        cache.put(8, "BLACK_LISTED");
        cache.put(256, "WAIT_AUTHENTICATED");
        cache.put(257, "WAIT_ASSOCIATED");
    }

    @Override
    public String getName() {
        return "locv10";
    }

    @Override
    @SuppressWarnings("unchecked cast")
    public void process(String stream, Map<String, Object> message, MessageCollector collector) {
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) message.get(LOC_NOTIFICATIONS);

        if (notifications != null) {
            for (Map<String, Object> notification : notifications) {
                String notificationType = (String) notification.get(LOC_NOTIFICATION_TYPE);
                if (notificationType == null) notificationType = "null";

                if (notificationType.equals("association")) {
                    log.trace("Mse10 event this event is a association, emitting " + notification.size());
                    processAssociation(message, collector);
                } else if (notificationType.equals("locationupdate")) {
                    log.trace("Mse10 event this event is a locationupdate, emitting " + notification.size());
                    processLocationUpdate(message, collector);
                } else {
                    log.warn("MSE version 10 notificationType is unknown: " + notificationType);
                }
            }
        }
    }

    @SuppressWarnings("unchecked cast")
    public void processAssociation(Map<String, Object> message, MessageCollector collector) {
        try {
            List<Map<String, Object>> messages = (ArrayList) message.get("notifications");

            for (Map<String, Object> msg : messages) {
                log.trace("Processing mse10Association " + msg);
                Map<String, Object> toCache = new HashMap<>();
                Map<String, Object> toDruid = new HashMap<>();

                String clientMac = (String) msg.get(LOC_DEVICEID);
                Object namespace_id = msg.get(NAMESPACE_UUID) == null ? "" : msg.get(NAMESPACE_UUID);

                if (msg.get(LOC_SSID) != null)
                    toCache.put(WIRELESS_ID, msg.get(LOC_SSID));

                if (msg.get(LOC_BAND) != null)
                    toCache.put(NMSP_DOT11PROTOCOL, msg.get(LOC_BAND));

                if (msg.get(LOC_STATUS) != null) {
                    Integer msgStatus = (Integer) msg.get(LOC_STATUS);
                    toCache.put(DOT11STATUS, cache.get(msgStatus));
                }

                if (msg.get(LOC_AP_MACADDR) != null)
                    toCache.put(WIRELESS_STATION, msg.get(LOC_AP_MACADDR));

                if (msg.get(LOC_USERNAME) != null && !msg.get(LOC_USERNAME).equals(""))
                    toCache.put(CLIENT_ID, msg.get(LOC_USERNAME));

                toDruid.putAll(toCache);
                toDruid.put(SENSOR_NAME, msg.get(LOC_SUBSCRIPTION_NAME));
                toDruid.put(CLIENT_MAC, clientMac);
                toDruid.put(TIMESTAMP, ((Long) msg.get(TIMESTAMP)) / 1000L);
                toDruid.put(TYPE, "mse10-association");
                toDruid.put(LOC_SUBSCRIPTION_NAME, msg.get(LOC_SUBSCRIPTION_NAME));

                String market = (String) msg.get(MARKET);
                if (market != null) {
                    toDruid.put(MARKET, market);
                }

                String marketUuid = (String) msg.get(MARKET_UUID);
                if (marketUuid != null) {
                    toDruid.put(MARKET_UUID, marketUuid);
                }

                String organization = (String) msg.get(ORGANIZATION);
                if (organization != null) {
                    toDruid.put(ORGANIZATION, organization);
                }

                String organizationUuid = (String) msg.get(ORGANIZATION_UUID);
                if (organizationUuid != null) {
                    toDruid.put(ORGANIZATION_UUID, organizationUuid);
                }

                String deployment = (String) msg.get(DEPLOYMENT);
                if (deployment != null) {
                    toDruid.put(DEPLOYMENT, deployment);
                }

                String deploymentUuid = (String) msg.get(DEPLOYMENT_UUID);
                if (deploymentUuid != null) {
                    toDruid.put(DEPLOYMENT_UUID, deploymentUuid);
                }

                String sensorName = (String) msg.get(SENSOR_NAME);
                if (sensorName != null) {
                    toDruid.put(SENSOR_NAME, sensorName);
                }

                String sensorUuid = (String) msg.get(SENSOR_UUID);
                if (sensorUuid != null) {
                    toDruid.put(SENSOR_UUID, sensorUuid);
                }

                store.put(clientMac + namespace_id, toCache);

                toDruid.put(CLIENT_PROFILE, "hard");

                Map<String, Object> storeEnrichment = storeManager.enrich(toDruid);
                storeEnrichment.putAll(toDruid);
                Map<String, Object> enrichmentEvent = enrichManager.enrich(storeEnrichment);

                String datasource = DATASOURCE;
                Object namespace = enrichmentEvent.get(Dimension.NAMESPACE_UUID);

                if (namespace != null) {
                    datasource = String.format("%s_%s", DATASOURCE, namespace);
                }

                Long counter = countersStore.get(datasource);

                if(counter == null){
                    counter = 0L;
                }

                counter++;
                countersStore.put(datasource, counter);

                Long flows = flowsNumber.get(datasource);

                if(flows != null){
                    enrichmentEvent.put("flows_count", flows);
                }

                collector.send(new OutgoingMessageEnvelope(OUTPUT_STREAM, clientMac, enrichmentEvent));
            }
        } catch (Exception ex) {
            log.warn("MSE10 association event dropped: " + message, ex);
        }
    }

    @SuppressWarnings("unchecked cast")
    public void processLocationUpdate(Map<String, Object> message, MessageCollector collector) {
        try {
            List<Map<String, Object>> messages = (ArrayList) message.get("notifications");

            for (Map<String, Object> msg : messages) {
                log.trace("Processing mse10LocationUpdate " + msg);

                Map<String, Object> toCache = new HashMap<>();
                Map<String, Object> toDruid = new HashMap<>();

                Object namespace_id = msg.get(NAMESPACE_UUID) == null ? "" : msg.get(NAMESPACE_UUID);
                String clientMac = (String) msg.get(LOC_DEVICEID);
                String locationMapHierarchy = (String) msg.get(LOC_MAP_HIERARCHY_V10);

                if (msg.get(LOC_AP_MACADDR) != null)
                    toCache.put(WIRELESS_STATION, msg.get(LOC_AP_MACADDR));

                if (msg.get(SSID) != null)
                    toCache.put(WIRELESS_ID, msg.get(SSID));

                if (locationMapHierarchy != null) {
                    String[] locations = locationMapHierarchy.split(">");

                    if (locations.length >= 1)
                        toCache.put(CAMPUS, locations[0]);
                    if (locations.length >= 2)
                        toCache.put(BUILDING, locations[1]);
                    if (locations.length >= 3)
                        toCache.put(FLOOR, locations[2]);
                    if (locations.length >= 4)
                        toCache.put(ZONE, locations[3]);
                }

                Map<String, Object> assocCache = store.get(clientMac + namespace_id);

                if (assocCache != null) {
                    toCache.putAll(assocCache);
                } else {
                    toCache.put(DOT11STATUS, "PROBING");
                }

                toDruid.putAll(toCache);
                toDruid.put(SENSOR_NAME, msg.get(LOC_SUBSCRIPTION_NAME));
                toDruid.put(LOC_SUBSCRIPTION_NAME, msg.get(LOC_SUBSCRIPTION_NAME));

                if (msg.containsKey(TIMESTAMP)) {
                    toDruid.put(TIMESTAMP, ((Long) msg.get(TIMESTAMP)) / 1000L);
                } else {
                    toDruid.put(TIMESTAMP, System.currentTimeMillis() / 1000L);
                }

                toDruid.put(CLIENT_MAC, clientMac);
                toDruid.put(TYPE, "mse10-location");

                if (!namespace_id.equals(""))
                    toDruid.put(NAMESPACE_UUID, namespace_id);

                for (String dimension : dimToDruid) {
                    Object value = msg.get(dimension);
                    if (value != null) {
                        toDruid.put(dimension, value);
                    }
                }

                store.put(clientMac + namespace_id, toCache);

                Map<String, Object> storeEnrichment = storeManager.enrich(toDruid);
                storeEnrichment.putAll(toDruid);
                Map<String, Object> enrichmentEvent = enrichManager.enrich(storeEnrichment);

                String datasource = DATASOURCE;
                Object namespace = enrichmentEvent.get(Dimension.NAMESPACE_UUID);

                if (namespace != null) {
                    datasource = String.format("%s_%s", DATASOURCE, namespace);
                }

                Long counter = countersStore.get(datasource);

                if(counter == null){
                    counter = 0L;
                }

                counter++;
                countersStore.put(datasource, counter);

                Long flows = flowsNumber.get(datasource);

                if(flows != null){
                    enrichmentEvent.put("flows_count", flows);
                }

                collector.send(new OutgoingMessageEnvelope(OUTPUT_STREAM, clientMac, enrichmentEvent));
            }
        } catch (Exception ex) {
            log.warn("MSE10 locationUpdate event dropped: " + message, ex);
        }
    }
}
