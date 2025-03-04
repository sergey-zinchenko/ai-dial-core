package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ResourceBaseTest;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PublicationUtilTest {

    @Mock
    private EncryptionService encryptionService;

    @Test
    public void testBuildTargetFolderForCustomAppFiles() {
        when(encryptionService.decrypt(any(String.class))).thenReturn("location/");
        assertEquals("folder/.appA/", PublicationUtil.buildTargetFolderForCustomAppFiles("applications/asdfoiefjio/folder/appA", encryptionService));
        assertEquals(".appA/", PublicationUtil.buildTargetFolderForCustomAppFiles("applications/asdfoiefjio/appA", encryptionService));
        assertThrows(IllegalArgumentException.class, () -> PublicationUtil.buildTargetFolderForCustomAppFiles("applications/asdfoiefjio/appA/", encryptionService));
        assertThrows(IllegalArgumentException.class, () -> PublicationUtil.buildTargetFolderForCustomAppFiles("prompts/asdfoiefjio/appA", encryptionService));
    }

    @Test
    void testConversationIdReplacement() {
        ResourceDescriptor targetResource1 = ResourceDescriptorFactory.fromDecoded(ResourceTypes.CONVERSATION, "bucketName", "bucket/location/", "conversation");
        verifyJson("""
                {
                "id": "conversations/bucketName/conversation",
                "name": "display_name",
                "model": {"id": "model_id"},
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "conversations/bucketName",
                "messages": [],
                "selectedAddons": ["R", "T", "G"],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """, PublicationUtil.replaceConversationLinks(ResourceBaseTest.CONVERSATION_BODY_1, targetResource1, Map.of()));

        ResourceDescriptor targetResource2 = ResourceDescriptorFactory.fromDecoded(ResourceTypes.CONVERSATION, "bucketName", "bucket/location/", "folder1/conversation");
        verifyJson("""
                {
                "id": "conversations/bucketName/folder1/conversation",
                "name": "display_name",
                "model": {"id": "model_id"},
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "conversations/bucketName/folder1",
                "messages": [],
                "selectedAddons": ["R", "T", "G"],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """, PublicationUtil.replaceConversationLinks(ResourceBaseTest.CONVERSATION_BODY_1, targetResource2, Map.of()));
    }

    @Test
    void testAttachmentLinksReplacement() {
        ResourceDescriptor targetResource = ResourceDescriptorFactory.fromDecoded(ResourceTypes.CONVERSATION, "bucketName", "bucket/location/", "conversation");
        String conversationBody = """
                {
                    "id": "conversations/bucketName2/folder1/conversation",
                    "name": "display_name",
                    "model": {"id": "model_id"},
                    "prompt": "system prompt",
                    "temperature": 1,
                    "folderId": "conversations/bucketName2/folder1",
                    "messages": [
                          {
                          "content": "The file you provided is a Dockerfile.",
                          "role": "assistant",
                          "custom_content": {
                            "attachments": [
                              {
                                "type": "application/octet-stream",
                                "title": "LICENSE",
                                "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE"
                              },
                              {
                                "type": "binary/octet-stream",
                                "title": "Dockerfile",
                                "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile"
                              },
                              {
                                "type": "application/vnd.dial.metadata+json",
                                "title": ".dockerignore",
                                "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                              }
                            ]
                          }
                        }
                    ],
                    "selectedAddons": ["R", "T", "G"],
                    "assistantModelId": "assistantId",
                    "lastActivityDate": 4848683153,
                    "playback": {
                        "messagesStack": [
                        {
                            "custom_content": {
                                "attachments": [
                                {
                                    "type": "application/octet-stream",
                                    "title": "LICENSE",
                                    "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE"
                                },
                                {
                                    "type": "binary/octet-stream",
                                    "title": "Dockerfile",
                                    "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile"
                                },
                                {
                                    "type": "application/vnd.dial.metadata+json",
                                    "title": ".dockerignore",
                                    "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                                }
                                ]
                            }
                        }
                        ]
                    },
                    "replay": {
                        "replayUserMessagesStack": [
                        {
                            "custom_content": {
                                "attachments": [
                                {
                                    "type": "application/octet-stream",
                                    "title": "LICENSE",
                                    "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE"
                                },
                                {
                                    "type": "binary/octet-stream",
                                    "title": "Dockerfile",
                                    "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile"
                                },
                                {
                                    "type": "application/vnd.dial.metadata+json",
                                    "title": ".dockerignore",
                                    "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                                }
                                ]
                            }
                        }
                        ]
                    }
                }
                """;

        verifyJson("""
                {
                    "id": "conversations/bucketName/conversation",
                    "name": "display_name",
                    "model": {"id": "model_id"},
                    "prompt": "system prompt",
                    "temperature": 1,
                    "folderId": "conversations/bucketName",
                    "messages": [
                          {
                          "content": "The file you provided is a Dockerfile.",
                          "role": "assistant",
                          "custom_content": {
                            "attachments": [
                              {
                                "type": "application/octet-stream",
                                "title": "LICENSE",
                                "url": "files/public/License"
                              },
                              {
                                "type": "binary/octet-stream",
                                "title": "Dockerfile",
                                "url": "files/public/Dockerfile"
                              },
                              {
                                "type": "application/vnd.dial.metadata+json",
                                "title": ".dockerignore",
                                "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                              }
                            ]
                          }
                        }
                    ],
                    "selectedAddons": ["R", "T", "G"],
                    "assistantModelId": "assistantId",
                    "lastActivityDate": 4848683153,
                    "playback" : {
                        "messagesStack" : [ {
                          "custom_content" : {
                            "attachments" : [ {
                              "type" : "application/octet-stream",
                              "title" : "LICENSE",
                              "url" : "files/public/License"
                            }, {
                              "type" : "binary/octet-stream",
                              "title" : "Dockerfile",
                              "url" : "files/public/Dockerfile"
                            }, {
                              "type" : "application/vnd.dial.metadata+json",
                              "title" : ".dockerignore",
                              "url" : "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                            } ]
                          }
                        } ]
                    },
                    "replay" : {
                        "replayUserMessagesStack" : [ {
                          "custom_content" : {
                            "attachments" : [ {
                              "type" : "application/octet-stream",
                              "title" : "LICENSE",
                              "url" : "files/public/License"
                            }, {
                              "type" : "binary/octet-stream",
                              "title" : "Dockerfile",
                              "url" : "files/public/Dockerfile"
                            }, {
                              "type" : "application/vnd.dial.metadata+json",
                              "title" : ".dockerignore",
                              "url" : "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                            } ]
                          }
                    } ]
                    }
                }
                """, PublicationUtil.replaceConversationLinks(conversationBody, targetResource, Map.of(
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE", "files/public/License",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile", "files/public/Dockerfile")));

        verifyJson("""
                {
                    "id": "conversations/bucketName/conversation",
                    "name": "display_name",
                    "model": {"id": "model_id"},
                    "prompt": "system prompt",
                    "temperature": 1,
                    "folderId": "conversations/bucketName",
                    "messages": [
                          {
                          "content": "The file you provided is a Dockerfile.",
                          "role": "assistant",
                          "custom_content": {
                            "attachments": [
                              {
                                "type": "application/octet-stream",
                                "title": "LICENSE",
                                "url": "files/public/License"
                              },
                              {
                                "type": "binary/octet-stream",
                                "title": "Dockerfile",
                                "url": "files/public/Dockerfile"
                              },
                              {
                                "type": "application/vnd.dial.metadata+json",
                                "title": ".dockerignore",
                                "url": "metadata/files/public/attachments/"
                              }
                            ]
                          }
                        }
                    ],
                    "selectedAddons": ["R", "T", "G"],
                    "assistantModelId": "assistantId",
                    "lastActivityDate": 4848683153,
                    "playback" : {
                        "messagesStack" : [ {
                          "custom_content" : {
                            "attachments" : [ {
                              "type" : "application/octet-stream",
                              "title" : "LICENSE",
                              "url" : "files/public/License"
                            }, {
                              "type" : "binary/octet-stream",
                              "title" : "Dockerfile",
                              "url" : "files/public/Dockerfile"
                            }, {
                              "type" : "application/vnd.dial.metadata+json",
                              "title" : ".dockerignore",
                              "url" : "metadata/files/public/attachments/"
                            } ]
                          }
                        } ]
                      },
                      "replay" : {
                        "replayUserMessagesStack" : [ {
                          "custom_content" : {
                            "attachments" : [ {
                              "type" : "application/octet-stream",
                              "title" : "LICENSE",
                              "url" : "files/public/License"
                            }, {
                              "type" : "binary/octet-stream",
                              "title" : "Dockerfile",
                              "url" : "files/public/Dockerfile"
                            }, {
                              "type" : "application/vnd.dial.metadata+json",
                              "title" : ".dockerignore",
                              "url" : "metadata/files/public/attachments/"
                            } ]
                          }
                        } ]
                      }
                    }
                }
                """, PublicationUtil.replaceConversationLinks(conversationBody, targetResource, Map.of(
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE", "files/public/License",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile", "files/public/Dockerfile",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/", "files/public/attachments/")));
    }

    private static void verifyJson(String expected, String actual) {
        try {
            assertEquals(ProxyUtil.MAPPER.readTree(expected).toPrettyString(), ProxyUtil.MAPPER.readTree(actual).toPrettyString());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
