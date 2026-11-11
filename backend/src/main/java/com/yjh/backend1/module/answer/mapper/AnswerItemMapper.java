package com.yjh.backend1.module.answer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjh.backend1.module.answer.entity.AnswerItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 答案明细 Mapper。
 * <p>
 * 刻意只用 {@link BaseMapper} 的通用方法，不写自定义 SQL：
 * {@code option_values} 是 JSON 列，靠实体上的
 * {@code @TableName(autoResultMap = true)} + {@code JacksonTypeHandler} 才能正确反序列化。
 * 自定义 {@code @Select} 默认不会套用 MyBatis-Plus 生成的 resultMap，
 * 很容易出现「查回来是字符串」的隐性 bug。用 Wrapper 拼条件既安全又能覆盖全部场景。
 */
@Mapper
public interface AnswerItemMapper extends BaseMapper<AnswerItem> {
}
