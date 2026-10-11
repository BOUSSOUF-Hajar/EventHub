package com.eventhub.notification.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Lit la boite de capture de Mailhog par son API HTTP, celle qu'utilise son interface web.
 *
 * Les emails sont relus a partir du message brut et decodes par Jakarta Mail : sujet et
 * corps accentues sont encodes sur le fil (MIME), les comparer tels quels ne marcherait pas.
 */
public class MailhogClient {

    public record ReceivedEmail(String from, String to, String subject, String body) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;

    public MailhogClient(String baseUrl) {
        this.http = RestClient.create(baseUrl);
    }

    public void deleteAll() {
        http.delete().uri("/api/v1/messages").retrieve().toBodilessEntity();
    }

    public List<ReceivedEmail> emails() {
        // Mailhog repond en "text/json", que RestClient ne sait pas convertir : on lit le
        // texte et on le parse nous-memes.
        String response = http.get().uri("/api/v2/messages?limit=250").retrieve().body(String.class);
        List<ReceivedEmail> emails = new ArrayList<>();
        try {
            for (JsonNode item : JSON.readTree(response).get("items")) {
                emails.add(parse(item.get("Raw").get("Data").asText()));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Reponse Mailhog illisible", e);
        }
        return emails;
    }

    public List<ReceivedEmail> emailsTo(String recipient) {
        return emails().stream().filter(email -> email.to().equals(recipient)).toList();
    }

    private ReceivedEmail parse(String raw) {
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            return new ReceivedEmail(
                    first(message.getFrom()),
                    first(message.getRecipients(Message.RecipientType.TO)),
                    message.getSubject(),
                    String.valueOf(message.getContent()));
        } catch (Exception e) {
            throw new IllegalStateException("Email Mailhog illisible", e);
        }
    }

    private static String first(Address[] addresses) {
        return addresses == null || addresses.length == 0 ? null : addresses[0].toString();
    }
}
