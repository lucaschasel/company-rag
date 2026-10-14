package com.enterprise.knowledge;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentSecurity {
    private final String clamdHost;
    private final int clamdPort;

    public DocumentSecurity(@Value("${app.clamd.host:}") String clamdHost,
                            @Value("${app.clamd.port:3310}") int clamdPort) {
        this.clamdHost = clamdHost; this.clamdPort = clamdPort;
    }

    /** 依据文件头魔数校验内容与扩展名一致，防止伪装文件。 */
    public void validate(Path path, String name) throws Exception {
        String lower = name.toLowerCase(Locale.ROOT);
        byte[] head;
        try (var in = Files.newInputStream(path)) { head = in.readNBytes(8); }
        if (lower.endsWith(".pdf") && !startsWith(head, "%PDF"))
            throw new IllegalArgumentException("文件内容与 .pdf 扩展名不符");
        if (lower.endsWith(".docx") && !startsWith(head, "PK"))
            throw new IllegalArgumentException("文件内容与 .docx 扩展名不符");
        if ((lower.endsWith(".txt") || lower.endsWith(".md")) && startsWith(head, "%PDF"))
            throw new IllegalArgumentException("文件内容与文本扩展名不符");
    }

    /** 若配置了 clamd 则做病毒扫描，未配置时跳过。 */
    public void scan(Path path) throws Exception {
        if (clamdHost == null || clamdHost.isBlank()) return;
        try (var socket = new Socket(clamdHost, clamdPort)) {
            var out = socket.getOutputStream();
            out.write("INSTREAM\n".getBytes(StandardCharsets.US_ASCII));
            byte[] data = Files.readAllBytes(path);
            out.write(new byte[]{(byte)(data.length >> 24), (byte)(data.length >> 16), (byte)(data.length >> 8), (byte)data.length});
            out.write(data);
            out.write(new byte[]{0, 0, 0, 0});
            out.flush();
            String resp = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            if (!resp.contains("OK")) throw new IllegalArgumentException("文件未通过病毒扫描");
        }
    }

    public void check(Path path, String name) throws Exception {
        validate(path, name);
        scan(path);
    }

    private static boolean startsWith(byte[] data, String sig) {
        byte[] s = sig.getBytes(StandardCharsets.US_ASCII);
        if (data.length < s.length) return false;
        for (int i = 0; i < s.length; i++) if (data[i] != s[i]) return false;
        return true;
    }
}
