package dev.zhiku.ingestion;

import dev.zhiku.platform.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 首版格式策略：仅支持 UTF-8 Markdown 或纯文本。忽略客户端提供的 content type。 */
final class UploadValidator {
  record Accepted(String mediaType, String fileName, String text, String sha256) {}

  private UploadValidator() {}

  static Accepted validate(String originalName, byte[] bytes) {
    var fileName = baseName(originalName);
    var dot = fileName.lastIndexOf('.');
    var ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    var mediaType = switch (ext) {
      case "md", "markdown" -> "text/markdown";
      case "txt" -> "text/plain";
      default -> throw ApiException.unprocessable("Only .md and .txt files are supported");
    };
    if (bytes.length == 0) throw ApiException.unprocessable("File is empty");
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      throw ApiException.unprocessable("File is not valid UTF-8 text");
    }
    if (text.startsWith("﻿")) text = text.substring(1);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') throw ApiException.unprocessable("File contains binary control characters");
    }
    if (text.isBlank()) throw ApiException.unprocessable("File has no text content");
    return new Accepted(mediaType, fileName, text, sha256(bytes));
  }

  private static String baseName(String name) {
    var n = name == null ? "" : name;
    n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1).strip();
    if (n.isEmpty()) throw ApiException.unprocessable("File name is required");
    return n.length() > 200 ? n.substring(n.length() - 200) : n;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
