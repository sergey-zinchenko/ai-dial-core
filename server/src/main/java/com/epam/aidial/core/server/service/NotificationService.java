package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.DeleteNotificationRequest;
import com.epam.aidial.core.server.data.Notification;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@AllArgsConstructor
@Slf4j
public class NotificationService {

    private static final String NOTIFICATION_RESOURCE_FILENAME = "notifications";

    private static final TypeReference<Map<String, Notification>> NOTIFICATIONS_TYPE = new TypeReference<>() {
    };

    private final ResourceService resourceService;
    private final EncryptionService encryptionService;


    public Notification createNotification(String bucketName, String bucketLocation, Notification notification) {
        ResourceDescriptor notificationResource = getNotificationResource(bucketName, bucketLocation);

        resourceService.computeResource(notificationResource, body -> {
            Map<String, Notification> notifications = decodeNotifications(body);
            notifications.put(notification.getId(), notification);

            return ProxyUtil.convertToString(notifications);
        });

        return notification;
    }

    public Set<Notification> listNotification(ProxyContext context) {
        ResourceDescriptor notificationResource = getNotificationResource(context, encryptionService);
        Map<String, Notification> notifications = decodeNotifications(resourceService.getResource(notificationResource));

        return new TreeSet<>(notifications.values());
    }

    public void deleteNotification(ProxyContext context, DeleteNotificationRequest request) {
        List<String> ids = request.ids();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Notification IDs cannot be empty");
        }
        ResourceDescriptor notificationResource = getNotificationResource(context, encryptionService);
        resourceService.computeResource(notificationResource, state -> {
            Map<String, Notification> notifications = decodeNotifications(state);
            ids.forEach(notifications::remove);

            return ProxyUtil.convertToString(notifications);
        });
    }

    private static ResourceDescriptor getNotificationResource(ProxyContext context, EncryptionService encryptionService) {
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        String bucketName = encryptionService.encrypt(bucketLocation);
        return getNotificationResource(bucketName, bucketLocation);
    }

    private static ResourceDescriptor getNotificationResource(String bucketName, String bucketLocation) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.NOTIFICATION, bucketName, bucketLocation, NOTIFICATION_RESOURCE_FILENAME);
    }

    private static Map<String, Notification> decodeNotifications(String json) {
        Map<String, Notification> notifications = ProxyUtil.convertToObject(json, NOTIFICATIONS_TYPE);
        return (notifications == null) ? new LinkedHashMap<>() : notifications;
    }
}
