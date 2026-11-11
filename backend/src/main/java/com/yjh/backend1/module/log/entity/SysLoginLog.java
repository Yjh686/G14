package com.yjh.backend1.module.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录日志，对应表 {@code sys_login_log}。只增不改，无逻辑删除字段。
 * <p>
 * 注意：账号不存在时 {@code userId} 为 NULL，但 {@code username} 一定记录，
 * 便于排查"有人在猜账号"。
 */
@Data
@TableName("sys_login_log")
public class SysLoginLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    /** 1=成功 0=失败 */
    private Integer result;
    private String reason;
    private String ip;
    private String userAgent;
    private LocalDateTime createTime;
}
