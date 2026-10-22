package com.bth.vendorrag.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import com.bth.vendorrag.execption.IngestionExtractionException;
import com.openai.errors.RateLimitException;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class IngestionService {

	private final Tika tika = new Tika();
	private final AutoDetectParser parser = new AutoDetectParser();
	private final VectorStore vectorStore;
	private final ChatClient chatClient; // Spring AI, for intent/entity extraction
	private final Driver neo4jDriver;

	@Value("${vendorrag.ingestion.fallback-on-rate-limit}")
	private boolean fallbackOnRateLimit;

	@Value("${spring.ai.vectorstore.neo4j.label}")
	private String neo4jLabel;

	@Value("${spring.ai.vectorstore.neo4j.embedding-dimension}")
	private int embeddingDimension;

	public IngestionService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder, Driver neo4jDriver) {
		this.vectorStore = vectorStore;
		this.chatClient = chatClientBuilder.build();
		this.neo4jDriver = neo4jDriver;
	}

	public record IngestionResult(String filename, String detectedType, String extractedText,
			Map<String, Object> metadata, List<String> chunkIds) {
	}

	public IngestionResult ingest(String filename, InputStream inputStream, String sourceTypeHint) throws IOException {

		// 1. Buffer so Tika can both sniff type AND extract content
		byte[] bytes = inputStream.readAllBytes();

		// 2. Detect actual MIME type (don't trust the extension/hint alone)
		String detectedMimeType = tika.detect(bytes, filename);

		// 3. Extract text + metadata via Tika (handles PDF, docx, images+OCR, eml/msg,
		// txt, html...)
		BodyContentHandler handler = new BodyContentHandler(-1); // no length limit
		Metadata tikaMetadata = new Metadata();
		ParseContext context = new ParseContext();

		try (TikaInputStream tis = TikaInputStream.get(bytes)) {
			parser.parse(tis, handler, tikaMetadata, context);
		} catch (TikaException | SAXException e) {
			throw new IOException("Failed to parse " + filename, e);
		}

		String rawText = handler.toString();

		// 4. Route to source-specific pre-processing before it hits the LLM extraction
		// step
		String effectiveSourceType = sourceTypeHint != null ? sourceTypeHint
				: classifySourceType(detectedMimeType, filename, rawText);

		String normalizedText = switch (effectiveSourceType) {
		case "whatsapp" -> normalizeWhatsAppExport(rawText);
		case "email" -> normalizeEmail(rawText, tikaMetadata);
		default -> rawText;
		};

		// 5. LLM-based structured extraction — pull travel entities regardless of
		// source format
		TravelExtraction extraction = extractStructuredData(normalizedText, effectiveSourceType);

		// 6. Chunk + embed + store
		// stub embedding when the embedding call itself is rate-limited, so
		// Neo4j insert/read can still be exercised without live OpenAI quota.
		List<Document> docs = chunkAndTagDocuments(normalizedText, filename, effectiveSourceType, extraction);

		List<String> chunkIds;
		try {
			vectorStore.add(docs);
			chunkIds = docs.stream().map(Document::getId).toList();
		} catch (RuntimeException e) {
			if (fallbackOnRateLimit && isRateLimit(e)) {
				System.err.println("[IngestionService] Embedding call rate-limited (429) — "
						+ "writing stub embeddings directly to Neo4j for testing. " + e.getMessage());
				chunkIds = writeDocsWithStubEmbeddings(docs);
			} else {
				throw e;
			}
		}
		Map<String, Object> metadataOut = new HashMap<>();
		metadataOut.put("mimeType", detectedMimeType);
		metadataOut.put("sourceType", effectiveSourceType);
		metadataOut.put("extraction", extraction);

		return new IngestionResult(filename, detectedMimeType, normalizedText, metadataOut,
				docs.stream().map(Document::getId).toList());
	}

	private boolean isRateLimit(Throwable e) {
		while (e != null) {
			if (e instanceof RateLimitException)
				return true;
			e = e.getCause();
		}
		return false;
	}

	// Bypasses the embedding model entirely — writes the chunk text + metadata
	// + a random stub vector directly via the Neo4j driver. Good enough to
	// confirm nodes land in Neo4j and can be read back; NOT semantically
	// searchable (the vectors are noise), so use similaritySearch only once
	// real embeddings are working again.
	private List<String> writeDocsWithStubEmbeddings(List<Document> docs) {
		List<String> ids = new ArrayList<>();
		Random random = new Random();

		try (Session session = neo4jDriver.session()) {
			for (Document doc : docs) {
				List<Double> stubEmbedding = random.doubles(embeddingDimension, -0.01, 0.01).boxed().toList();

				// neo4jLabel comes from application config, not user input, so
				// string-interpolating it into the label position is acceptable
				// here (Cypher doesn't support parameterized labels).
				String cypher = "CREATE (c:" + neo4jLabel + " {id: $id, text: $text, embedding: $embedding}) "
						+ "SET c += $metadata";

				session.run(cypher, Map.of("id", doc.getId(), "text", doc.getText(), "embedding", stubEmbedding,
						"metadata", doc.getMetadata()));
				ids.add(doc.getId());
			}
		}
		return ids;
	}

	private String classifySourceType(String mimeType, String filename, String text) {
		if (mimeType.startsWith("message/") || mimeType.contains("outlook"))
			return "email";
		if (filename != null && filename.toLowerCase().endsWith(".txt")
				&& text.matches("(?s).*\\d{1,2}/\\d{1,2}/\\d{2,4},\\s*\\d{1,2}:\\d{2}.*-.*:.*")) {
			return "whatsapp"; // heuristic: WhatsApp export line pattern
		}
		if (mimeType.startsWith("image/"))
			return "image";
		if (mimeType.equals("application/pdf"))
			return "pdf";
		if (mimeType.contains("word"))
			return "docx";
		return "document";
	}

	// WhatsApp exports are just timestamped chat lines — strip noise, keep
	// sender+message+date
	private String normalizeWhatsAppExport(String rawText) {
		return Arrays.stream(rawText.split("\\R")).filter(line -> line.matches("^\\d{1,2}/\\d{1,2}/\\d{2,4},.*"))
				.collect(Collectors.joining("\n"));
	}

	private String normalizeEmail(String rawText, Metadata tikaMetadata) {
		String subject = tikaMetadata.get("subject");
		String from = tikaMetadata.get("Message-From");
		return (subject != null ? "Subject: " + subject + "\n" : "") + (from != null ? "From: " + from + "\n" : "")
				+ rawText;
	}

	private TravelExtraction extractStructuredData(String text, String sourceType) throws IngestionExtractionException {

		try {
			return chatClient.prompt().user(u -> u.text("""
					You are extracting structured travel data from a document.
					The source format is: {sourceType}.

					Read the content carefully and classify it before extracting fields.

					Step 1 — Classify documentType as one of:

					- "booking_confirmation": a confirmed single-vendor, single-stay booking
					  (has a booking/confirmation reference and one check-in/check-out date range)

					- "package_flyer": a marketing flyer or quote for a multi-city/multi-leg
					  holiday package (lists several destinations, each with a number of nights,
					  usually a per-person starting price and no booking reference)

					- "invoice": a billed/paid document with itemized charges

					- "itinerary": a day-by-day travel plan without pricing/marketing framing


					Step 2 — Extract fields based on that classification:

					- vendor:
					  The company/DMC/agency name issuing the document.

					- packageName:
					  The marketing name of the trip if present
					  (e.g. a destination used as a title, like "Kerala").
					  Otherwise null.

					- tagline:
					  Any marketing tagline/slogan text, otherwise null.

					- legs:
					  A list containing location and nights for EVERY distinct
					  destination or stay mentioned, in order.

					  For a single-stay booking, this is a one-element list.

					  Do not merge multiple destinations into one location string.

					- totalNights / totalDays:
					  The overall trip duration if stated or clearly derivable
					  by summing leg nights, otherwise null.

					- pricing:
					  Extract the following:
					  - amount
					  - currency
					  - unit
					  - minGuests

					  The unit should be one of:
					  - "per_person"
					  - "per_couple"
					  - "total"
					  - null if unclear

					  minGuests is the minimum group size required for that price,
					  otherwise null.

					- inclusions:
					  A list of what is included, such as hotels, meals,
					  transport, sightseeing, etc., as short labels.
					  Return an empty list if none are present.

					- specialOffer:
					  Any complementary or bonus item called out
					  (e.g. a free activity), otherwise null.

					- bookingReference:
					  A confirmation, PNR, booking number, or reference
					  if present, otherwise null.

					- contact:
					  Extract:
					  - phone
					  - email

					  Return null for fields that are not present.


					Important rules:

					1. Only extract what is explicitly present or clearly derivable.
					2. Do not guess or invent values.
					3. Leave fields null when information is unavailable.
					4. Return an empty list when a list has no available values.
					5. Preserve the order of destinations.
					6. Return only the structured response.
					7. Do not return explanations.
					8. Do not return Markdown.
					9. Do not wrap the response in a JSON code block.

					Content:

					{text}
					""").param("sourceType", sourceType).param("text", text)).call().entity(TravelExtraction.class);

		} catch (RateLimitException e) {
			if (fallbackOnRateLimit) {
				System.err.println("[IngestionService] OpenAI rate-limited (429) — "
						+ "vendorrag.ingestion.fallback-on-rate-limit=true, using default TravelExtraction. "
						+ e.getMessage());
				return buildDefaultExtraction(sourceType);
			}
			throw new IngestionExtractionException(
					"AI extraction failed for source type '" + sourceType + "' due to rate limiting: " + e.getMessage(),
					e);

		} catch (RuntimeException e) {

			throw new IngestionExtractionException(
					"AI extraction failed for source type '" + sourceType + "': " + e.getMessage(), e);
		}
	}

	private TravelExtraction buildDefaultExtraction(String sourceType) {
		log.error("Inside buildDefaultExtraction");
		return new TravelExtraction("Unknown Vendor (rate-limited fallback)", // vendor
				"itinerary", // documentType
				"Test Package", // packageName
				null, // tagline
				List.of(new TravelExtraction.Leg("Test City", 2)), // legs
				2, // totalNights
				3, // totalDays
				new TravelExtraction.Pricing(9999.0, "INR", "per_person", 2), // pricing
				List.of("Hotel", "Breakfast"), // inclusions
				null, // specialOffer
				"TEST-REF-" + System.currentTimeMillis(), // bookingReference
				new TravelExtraction.ContactInfo("0000000000", "test@example.com") // contact
		);
	}

	private List<Document> chunkAndTagDocuments(String text, String filename, String sourceType,
			TravelExtraction extraction) {
		var splitter = TokenTextSplitter.builder().withChunkSize(800).withMinChunkSizeChars(350)
				.withMinChunkLengthToEmbed(5).withKeepSeparator(true).build();

		Map<String, Object> baseMetadata = new HashMap<>();
		baseMetadata.put("filename", filename);
		baseMetadata.put("sourceType", sourceType);
		baseMetadata.put("documentType", extraction.documentType() != null ? extraction.documentType() : "unknown");
		baseMetadata.put("vendor", extraction.vendor() != null ? extraction.vendor() : "unknown");

		if (extraction.packageName() != null) {
			baseMetadata.put("packageName", extraction.packageName());
		}

		if (extraction.legs() != null && !extraction.legs().isEmpty()) {
// Flatten leg locations into a searchable comma-joined string —
// most vector store metadata filters don't support list-of-object fields well
			String locations = extraction.legs().stream().map(TravelExtraction.Leg::location).filter(Objects::nonNull)
					.collect(Collectors.joining(", "));
			baseMetadata.put("locations", locations);
			baseMetadata.put("legCount", extraction.legs().size());
		}

		if (extraction.totalNights() != null) {
			baseMetadata.put("totalNights", extraction.totalNights());
		}
		if (extraction.totalDays() != null) {
			baseMetadata.put("totalDays", extraction.totalDays());
		}

		if (extraction.pricing() != null) {
			TravelExtraction.Pricing pricing = extraction.pricing();
			if (pricing.amount() != null)
				baseMetadata.put("priceAmount", pricing.amount());
			if (pricing.currency() != null)
				baseMetadata.put("priceCurrency", pricing.currency());
			if (pricing.unit() != null)
				baseMetadata.put("priceUnit", pricing.unit());
			if (pricing.minGuests() != null)
				baseMetadata.put("minGuests", pricing.minGuests());
		}

		if (extraction.bookingReference() != null) {
			baseMetadata.put("bookingReference", extraction.bookingReference());
		}

		if (extraction.contact() != null) {
			if (extraction.contact().phone() != null) {
				baseMetadata.put("contactPhone", extraction.contact().phone());
			}
			if (extraction.contact().email() != null) {
				baseMetadata.put("contactEmail", extraction.contact().email());
			}
		}

// Prepend a compact summary line to the raw text so each chunk's embedding
// captures package-level context even if the chunk itself only covers one leg
		String contextPrefix = buildContextPrefix(extraction);
		Document base = new Document(contextPrefix + text, baseMetadata);

		return splitter.apply(List.of(base));
	}

	private String buildContextPrefix(TravelExtraction extraction) {
		StringBuilder sb = new StringBuilder();
		if (extraction.packageName() != null) {
			sb.append("Package: ").append(extraction.packageName()).append(". ");
		}
		if (extraction.vendor() != null) {
			sb.append("Vendor: ").append(extraction.vendor()).append(". ");
		}
		if (extraction.legs() != null && !extraction.legs().isEmpty()) {
			String legsSummary = extraction.legs().stream().filter(l -> l.location() != null)
					.map(l -> l.nights() != null ? l.nights() + "N " + l.location() : l.location())
					.collect(Collectors.joining(", "));
			sb.append("Itinerary: ").append(legsSummary).append(". ");
		}
		if (sb.length() > 0)
			sb.append("\n\n");
		return sb.toString();
	}

}
