package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 诊断模板：诊断台场景模板 / 巡检体检命令集 / 急诊取证序列三类（kind），统一资产化。
 * items 为 [{"name":"步骤名","command":"arthas 命令"}] JSON 数组，命令可含 {{占位符}}。
 */
@Entity
@Table(name = "diag_template")
public class DiagTemplateEntity {

    public static final String KIND_DIAG = "diag";
    public static final String KIND_CHECKUP = "checkup";
    public static final String KIND_RESCUE = "rescue";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;
    public String kind;
    /** 场景分类展示（如 CPU / 内存 / 自定义），诊断台标签分组用 */
    public String scene;
    public String itemsJson;
    /** 绑定应用（空 = 通用） */
    public String bindAppId;
    public boolean builtIn;
    public boolean enabled = true;
    public int sort;
    public String createdBy;
    public Instant createdAt;
    public Instant updatedAt;

    public DiagTemplateEntity() {
    }

    public DiagTemplateEntity(String name, String kind, String scene, String itemsJson,
                              String bindAppId, boolean builtIn, int sort, String createdBy) {
        this.name = name;
        this.kind = kind;
        this.scene = scene;
        this.itemsJson = itemsJson;
        this.bindAppId = bindAppId;
        this.builtIn = builtIn;
        this.enabled = true;
        this.sort = sort;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }
}
