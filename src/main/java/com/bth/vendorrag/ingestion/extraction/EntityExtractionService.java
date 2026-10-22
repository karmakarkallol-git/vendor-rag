package com.bth.vendorrag.ingestion.extraction;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class EntityExtractionService {

    private final ChatClient chatClient;

    public EntityExtractionService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public PackageExtraction extract(String documentText) {
        return chatClient.prompt()
                .user(u -> u.text("""
                        Extract the holiday package details from the document text below.
                        If a field isn't present in the text, leave it null — do not guess or invent values.

                        Document:
                        {text}
                        """)
                        .param("text", documentText))
                .call()
                .entity(PackageExtraction.class);
    }
}