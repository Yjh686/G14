package com.g14.g14.login.mapper;

import com.g14.g14.login.entity.ClassInfo;
import com.g14.g14.login.entity.ClassStudent;
import com.g14.g14.login.entity.Questionnaire;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 权限演示所需的班级 / 问卷查询 Mapper。
 *
 * <p>班级与问卷模块本体尚未交付，这里只提供权限校验层所需的最小读操作，
 * 用于验证需求中的两条数据级规则：
 * <ul>
 *   <li>老师只能管理自己创建的班级与问卷（属主校验）；</li>
 *   <li>学生只能查看自己所在班级的问卷（数据范围校验）。</li>
 * </ul>
 */
@Mapper
public interface PermissionMapper {

    @Select("SELECT id, name, invite_code, teacher_id, created_at FROM class_info WHERE id = #{id}")
    ClassInfo selectClassById(@Param("id") Long id);

    @Select("SELECT COUNT(1) FROM class_student WHERE class_id = #{classId} AND student_id = #{studentId}")
    int countMembership(@Param("classId") Long classId, @Param("studentId") Long studentId);

    @Select("SELECT id, class_id, student_id, created_at FROM class_student "
            + "WHERE class_id = #{classId} AND student_id = #{studentId} LIMIT 1")
    ClassStudent selectMembership(@Param("classId") Long classId, @Param("studentId") Long studentId);

    @Select("SELECT id, class_id, title, creator_id, status, created_at FROM questionnaire WHERE id = #{id}")
    Questionnaire selectQuestionnaireById(@Param("id") Long id);
}
