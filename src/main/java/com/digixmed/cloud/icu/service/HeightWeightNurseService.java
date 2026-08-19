package com.digixmed.cloud.icu.service;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 身高体重记录者锁定服务
 *
 * 业务规则：
 *   1. 入科第一条回传时，身高体重的记录者必须与同一次回传的体温记录者一致；
 *   2. 之后（7天分页）所有身高体重回传，一直沿用第一次锁定的记录者，不再跟随当时的护士变化。
 *
 * 实现方式：
 *   集合 vitalsign_hw_nurse，以 Mongo patient._id（pid）为唯一键。
 *   使用 findAndModify + upsert + setOnInsert 原子写入，保证多实例并发下"第一次写入者胜出"，
 *   不会因为两个节点同时扫描而把记录者覆盖成不同的人。
 *
 * 必须创建唯一索引：
 *   db.vitalsign_hw_nurse.createIndex({ "pid": 1 }, { unique: true })
 */
@Service
public class HeightWeightNurseService {

    private static final Logger log = LoggerFactory.getLogger(HeightWeightNurseService.class);

    public static final String COLLECTION = "vitalsign_hw_nurse";

    /** 兜底记录者：与 PushService.buildDataXml 的默认值保持一致 */
    public static final String DEFAULT_NURSE_ID = "041660";
    public static final String DEFAULT_NURSE_NAME = "陈琳";

    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * 记录者引用
     */
    public static class NurseRef {
        private final String id;
        private final String name;
        private final boolean pinned;

        public NurseRef(String id, String name, boolean pinned) {
            this.id = (id == null || id.trim().isEmpty()) ? DEFAULT_NURSE_ID : id.trim();
            this.name = (name == null || name.trim().isEmpty()) ? DEFAULT_NURSE_NAME : name.trim();
            this.pinned = pinned;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        /** 是否已经落库锁定（false 表示只是本次兜底，后续仍可被正式锁定） */
        public boolean isPinned() {
            return pinned;
        }

        @Override
        public String toString() {
            return "NurseRef{id=" + id + ", name=" + name + ", pinned=" + pinned + "}";
        }
    }

    /**
     * 锁定记录者（首次写入生效，之后调用一律返回已锁定值）
     *
     * @param pid       Mongo patient._id
     * @param nurseId   记录者ID（通常来自体温 payload）
     * @param nurseName 记录者姓名（通常来自体温 payload）
     * @param source    来源说明，仅用于排查
     * @return 最终生效的记录者
     */
    public NurseRef pin(String pid, String nurseId, String nurseName, String source) {
        if (pid == null || pid.trim().isEmpty()) {
            return new NurseRef(nurseId, nurseName, false);
        }

        String effectiveId = (nurseId == null || nurseId.trim().isEmpty()) ? DEFAULT_NURSE_ID : nurseId.trim();
        String effectiveName = (nurseName == null || nurseName.trim().isEmpty()) ? DEFAULT_NURSE_NAME : nurseName.trim();

        try {
            Query query = new Query(Criteria.where("pid").is(pid));
            Update update = new Update()
                    .setOnInsert("pid", pid)
                    .setOnInsert("recordNurseId", effectiveId)
                    .setOnInsert("recordNurseName", effectiveName)
                    .setOnInsert("source", source)
                    .setOnInsert("createdAt", new Date());

            FindAndModifyOptions options = FindAndModifyOptions.options().upsert(true).returnNew(true);
            Document doc = mongoTemplate.findAndModify(query, update, options, Document.class, COLLECTION);

            if (doc != null) {
                String pinnedId = doc.getString("recordNurseId");
                String pinnedName = doc.getString("recordNurseName");
                if (!effectiveName.equals(pinnedName)) {
                    log.info("HW_NURSE pid={} 已锁定记录者={}（本次传入={}），沿用锁定值",
                            pid, pinnedName, effectiveName);
                }
                return new NurseRef(pinnedId, pinnedName, true);
            }
        } catch (Exception e) {
            log.warn("HW_NURSE pid={} 锁定记录者异常，本次使用传入值: {}", pid, e.getMessage());
        }

        return new NurseRef(effectiveId, effectiveName, false);
    }

    /**
     * 解析记录者（用于 7 天分页等非入科场景）
     *
     * 顺序：
     *   1. 已锁定 → 直接返回；
     *   2. 未锁定 → 回查推送队列中该患者最早的一条体温（1001）记录，用它的记录者补锁定；
     *   3. 仍拿不到 → 返回默认值，且不落库锁定，留给后续入科链路正式锁定。
     */
    public NurseRef resolve(String pid) {
        if (pid == null || pid.trim().isEmpty()) {
            return new NurseRef(null, null, false);
        }

        try {
            Document existing = mongoTemplate.findOne(
                    new Query(Criteria.where("pid").is(pid)), Document.class, COLLECTION);
            if (existing != null) {
                return new NurseRef(existing.getString("recordNurseId"),
                        existing.getString("recordNurseName"), true);
            }

            // 回查最早的一条体温回传记录，用它的记录者补锁定
            Query earliestTemp = new Query(Criteria.where("mongoPid").is(pid)
                    .and("vitalsignType").is("1001"))
                    .with(Sort.by(Sort.Direction.ASC, "createdAt"))
                    .limit(1);
            Document tempDoc = mongoTemplate.findOne(earliestTemp, Document.class,
                    IntermediateService.PUSH_COLLECTION);
            if (tempDoc != null) {
                log.info("HW_NURSE pid={} 未锁定，使用最早体温回传记录的记录者补锁定", pid);
                return pin(pid, tempDoc.getString("recordNurseId"),
                        tempDoc.getString("recordNurseName"), "EARLIEST_TEMPERATURE");
            }

            log.info("HW_NURSE pid={} 未锁定且无体温回传记录，本次使用默认记录者（不落库锁定）", pid);
        } catch (Exception e) {
            log.warn("HW_NURSE pid={} 解析记录者异常: {}", pid, e.getMessage());
        }

        return new NurseRef(null, null, false);
    }
}
