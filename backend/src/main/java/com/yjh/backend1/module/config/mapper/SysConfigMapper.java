package com.yjh.backend1.module.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjh.backend1.module.config.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {

    /**
     * 按配置键查，<b>包含已逻辑删除的行</b>。
     * <p>
     * MyBatis-Plus 的 {@code selectOne} 会自动附加 {@code deleted=0}，所以查不到
     * 被删过的那一行；而 {@code uk_key} 只挂在 {@code config_key} 上，被删的行仍占着
     * 这个键名。新增配置项时必须先看有没有「墓碑行」，否则 insert 会直接撞唯一键。
     */
    @Select("SELECT * FROM sys_config WHERE config_key = #{key} LIMIT 1")
    SysConfig selectByKeyIncludeDeleted(@Param("key") String key);

    /**
     * 复活一个墓碑行并覆盖其全部属性。
     * <p>
     * 不能用 {@code updateById}：它同样会拼上 {@code deleted=0}，删掉的行更新不到。
     */
    @Update("""
            UPDATE sys_config
               SET config_value = #{value},
                   config_type  = #{type},
                   group_name   = #{group},
                   description  = #{description},
                   is_public    = #{isPublic},
                   builtin      = 0,
                   deleted      = 0,
                   update_by    = #{updateBy},
                   update_time  = NOW()
             WHERE id = #{id}
            """)
    int revive(@Param("id") Long id,
               @Param("value") String value,
               @Param("type") String type,
               @Param("group") String group,
               @Param("description") String description,
               @Param("isPublic") Integer isPublic,
               @Param("updateBy") Long updateBy);
}
