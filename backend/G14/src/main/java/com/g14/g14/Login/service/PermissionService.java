package com.g14.g14.login.service;

import com.g14.g14.login.common.BizException;
import com.g14.g14.login.common.ResultCode;
import com.g14.g14.login.entity.ClassInfo;
import com.g14.g14.login.entity.Questionnaire;
import com.g14.g14.login.mapper.PermissionMapper;
import com.g14.g14.login.security.AuthContext;
import com.g14.g14.login.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * ============ 数据级权限（资源属主 / 数据范围）校验 ============
 *
 * <p>接口鉴权中间件只解决「角色维」的粗粒度控制（谁能调这个接口）；
 * 本类解决「数据维」的细粒度控制（能操作<b>哪一条</b>数据），对应需求 4 的两条规则：
 * <ul>
 *   <li>老师只能管理<b>自己创建</b>的班级和问卷；</li>
 *   <li>学生只能查看<b>自己所在班级</b>的问卷。</li>
 * </ul>
 *
 * <p><b>安全设计：越权与不存在返回完全一致的响应。</b>
 * 无论「资源不存在」还是「存在但无权访问」，都抛同一个 {@link ResultCode}
 * （班级 404/40402、问卷 404/40403），且文案完全相同。
 * 否则攻击者可以通过对比响应差异（404 vs 403）枚举出系统里存在哪些 ID。
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final PermissionMapper permissionMapper;

    public PermissionService(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    /**
     * 校验当前用户对指定班级的可见性。
     *
     * <ul>
     *   <li>管理员：全部可见；</li>
     *   <li>老师：仅自己创建的班级；</li>
     *   <li>学生：仅自己已加入的班级。</li>
     * </ul>
     *
     * @throws BizException 404/40402（不存在或无权访问，二者不可区分）
     */
    public ClassInfo requireClassVisible(Long classId) {
        LoginUser me = AuthContext.require();
        ClassInfo clazz = permissionMapper.selectClassById(classId);

        if (clazz != null) {
            if (me.isAdmin()) {
                return clazz;
            }
            if (me.isTeacher() && Objects.equals(clazz.getTeacherId(), me.getUserId())) {
                return clazz;
            }
            if (me.isStudent() && permissionMapper.countMembership(classId, me.getUserId()) > 0) {
                return clazz;
            }
        }

        log.warn("班级访问被拦截: userId={}, role={}, classId={}, exists={}",
                me.getUserId(), me.getRole(), classId, clazz != null);
        throw new BizException(ResultCode.CLASS_NOT_FOUND);
    }

    /**
     * 校验当前用户对指定问卷的可见性。
     *
     * <ul>
     *   <li>管理员：全部可见；</li>
     *   <li>老师：自己创建的问卷，或自己名下班级的问卷；</li>
     *   <li>学生：仅「已发布」且所属班级是自己已加入班级的问卷（草稿不可见）。</li>
     * </ul>
     *
     * @throws BizException 404/40403（不存在、未发布、无权访问三者不可区分）
     */
    public Questionnaire requireQuestionnaireVisible(Long questionnaireId) {
        LoginUser me = AuthContext.require();
        Questionnaire questionnaire = permissionMapper.selectQuestionnaireById(questionnaireId);

        if (questionnaire != null) {
            if (me.isAdmin()) {
                return questionnaire;
            }
            ClassInfo clazz = permissionMapper.selectClassById(questionnaire.getClassId());

            if (me.isTeacher()) {
                boolean isCreator = Objects.equals(questionnaire.getCreatorId(), me.getUserId());
                boolean ownsClass = clazz != null && Objects.equals(clazz.getTeacherId(), me.getUserId());
                if (isCreator || ownsClass) {
                    return questionnaire;
                }
            } else if (me.isStudent()) {
                boolean inClass = clazz != null
                        && permissionMapper.countMembership(clazz.getId(), me.getUserId()) > 0;
                if (questionnaire.published() && inClass) {
                    return questionnaire;
                }
            }
        }

        log.warn("问卷访问被拦截: userId={}, role={}, questionnaireId={}, exists={}",
                me.getUserId(), me.getRole(), questionnaireId, questionnaire != null);
        throw new BizException(ResultCode.QUESTIONNAIRE_NOT_FOUND);
    }
}
