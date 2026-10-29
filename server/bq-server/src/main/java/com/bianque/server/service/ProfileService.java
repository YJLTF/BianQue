package com.bianque.server.service;

import com.bianque.core.diag.DiagResult;
import com.bianque.server.store.DiagArtifactEntity;
import com.bianque.server.store.DiagArtifactJpa;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 性能剖析（火焰图）：arthas profiler 经命令通道统一操作（本机/探针/拟真通道一致）——
 * `profiler start` 采样 → `profiler stop --format html` 产物 → `cat` 产物路径回传文本 →
 * 落 diag_artifact，平台同源 iframe 渲染。产物上限 20MB；保留天数由参数中心/清理任务管理。
 */
@Service
public class ProfileService {

    /** profiler stop 输出中的产物路径（.html 结尾的路径 token） */
    private static final Pattern OUTPUT_PATH = Pattern.compile("(\\S+\\.html)");
    private static final int MAX_ARTIFACT_CHARS = 20 * 1024 * 1024;

    private final DiagService diag;
    private final DiagArtifactJpa artifacts;

    public ProfileService(DiagService diag, DiagArtifactJpa artifacts) {
        this.diag = diag;
        this.artifacts = artifacts;
    }

    /** 开始 CPU 采样 */
    public Map<String, Object> start(String instanceId, String operator) {
        DiagResult r = diag.execute(instanceId, "profiler start", "剖析", operator);
        if (!r.success()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "profiler start 失败：" + brief(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        out.put("costMs", r.costMs());
        out.put("output", brief(r));
        return out;
    }

    /** 停止采样并回传火焰图工件 */
    public Map<String, Object> stop(String instanceId, String operator) {
        DiagResult r = diag.execute(instanceId, "profiler stop --format html", "剖析", operator);
        if (!r.success()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "profiler stop 失败（可能未在采样中）：" + brief(r));
        }
        String path = findOutputPath(r.output());
        if (path == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "未在输出中找到产物路径：" + brief(r));
        }
        DiagResult cat = diag.execute(instanceId, "cat " + path, "剖析", operator);
        if (!cat.success() || cat.output() == null || cat.output().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "产物回传失败（cat " + path + "）：" + brief(cat));
        }
        if (cat.output().length() > MAX_ARTIFACT_CHARS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "产物超过 20MB 上限（" + cat.output().length() / 1024 / 1024 + "MB），请缩短采样时长");
        }
        DiagArtifactEntity saved = artifacts.save(new DiagArtifactEntity(instanceId, "flame-html", cat.output()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("artifactId", saved.id);
        out.put("path", path);
        out.put("sizeBytes", saved.sizeBytes);
        out.put("url", "/api/diag/artifact/" + saved.id);
        return out;
    }

    static String findOutputPath(String output) {
        if (output == null) {
            return null;
        }
        Matcher m = OUTPUT_PATH.matcher(output);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static String brief(DiagResult r) {
        String s = r.error() != null && !r.error().isBlank() ? r.error() : r.output();
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
