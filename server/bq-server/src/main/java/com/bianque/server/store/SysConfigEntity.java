package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 平台参数中心：键值可配置项（管理设置页维护）。键集与默认值由 SysConfigService 的
 * 参数注册表定义并启动播种；此处只存"当前生效值 + 最近修改人"，红线项不入注册表即不可配。
 */
@Entity
@Table(name = "sys_config")
public class SysConfigEntity {

    @Id
    public String configKey;

    /** 当前生效值（字符串形态，按注册表的 valueType 解析；缺行时回落注册表默认值） */
    public String configValue;

    public String updatedBy;
    public Instant updatedAt;

    public SysConfigEntity() {
    }

    public SysConfigEntity(String configKey, String configValue) {
        this.configKey = configKey;
        this.configValue = configValue;
    }
}
