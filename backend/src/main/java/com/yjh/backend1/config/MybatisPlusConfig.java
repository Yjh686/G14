package com.yjh.backend1.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.yjh.backend1.security.UserContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.mybatis.spring.annotation.MapperScan;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置：分页插件、防全表更新删除、审计字段自动填充。
 */
@Configuration
@MapperScan("com.yjh.backend1.module.**.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 分页插件（必须在防攻击插件之前加，否则 count 语句可能被改写）
        PaginationInnerInterceptor page = new PaginationInnerInterceptor(DbType.MYSQL);
        page.setMaxLimit(500L);          // 单页上限，防止 size=999999 拖库
        page.setOverflow(false);         // 超页返回空列表，而不是回到第一页
        interceptor.addInnerInterceptor(page);

        // 阻断没有 where 条件的 update / delete，避免误操作清表
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    /**
     * 审计字段自动填充。
     * <p>
     * createBy / updateBy 取当前登录用户；定时任务、初始化脚本等无登录态场景填 null，
     * 不写 0，避免后来人误以为 id=0 是个真实用户。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
                Long uid = UserContext.currentUserIdOrNull();
                if (uid != null) {
                    strictInsertFill(metaObject, "createBy", Long.class, uid);
                    strictInsertFill(metaObject, "updateBy", Long.class, uid);
                }
                strictInsertFill(metaObject, "deleted", Integer.class, 0);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
                Long uid = UserContext.currentUserIdOrNull();
                if (uid != null) {
                    strictUpdateFill(metaObject, "updateBy", Long.class, uid);
                }
            }
        };
    }
}
