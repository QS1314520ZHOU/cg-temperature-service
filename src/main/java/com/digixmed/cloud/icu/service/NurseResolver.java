package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.NurseInfo;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves nurse (trueName) from bedside records.
 *
 * Resolution order:
 * 1. Query bedside collection for code=param_Yishi within time range -> use editUser -> look up account
 * 2. Fallback to editUser from the source record -> look up account
 * 3. Return not_found if account missing or trueName empty
 */
@Service
public class NurseResolver {

    private static final Logger log = LoggerFactory.getLogger(NurseResolver.class);

    @Autowired
    private MongoTemplate mongoTemplate;

    // Bounded TTL cache for account lookups: key = editUser ID string, value = CacheEntry
    private final ConcurrentHashMap<String, CacheEntry> accountCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 1000;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Resolve nurse name from the given context.
     *
     * @param sourceRecord the bedside record being processed (must not be null)
     * @param pid          patient MongoDB ID
     * @param startTime    window start (inclusive)
     * @param endTime      window end (exclusive)
     * @return NurseInfo with resolved name or not_found status
     */
    public NurseInfo resolve(Document sourceRecord, String pid, Date startTime, Date endTime) {
        // Step 1: try param_Yishi
        String editUserFromParamYishi = findEditUserFromParamYishi(pid, startTime, endTime);
        if (editUserFromParamYishi != null) {
            AccountInfo info = lookupAccountInfo(editUserFromParamYishi);
            if (info != null) {
                return NurseInfo.builder()
                        .nurseId(info.username)
                        .nurseName(info.trueName)
                        .source("param_Yishi")
                        .reasonCode(null)
                        .build();
            }
        }

        // Step 2: fallback to source record editUser
        String editUserFromRecord = extractEditUser(sourceRecord);
        if (editUserFromRecord != null) {
            AccountInfo info = lookupAccountInfo(editUserFromRecord);
            if (info != null) {
                return NurseInfo.builder()
                        .nurseId(info.username)
                        .nurseName(info.trueName)
                        .source("source_record")
                        .reasonCode(null)
                        .build();
            }
        }

        // Step 3: not found
        return NurseInfo.builder()
                .nurseId("041660")
                .nurseName("")
                .source("not_found")
                .reasonCode("NURSE_NOT_FOUND")
                .build();
    }

    /**
     * 按 responsibleNurse 姓名解析护士
     *
     * @param responsibleNurseName patient.responsibleNurse（护士姓名）
     * @return NurseInfo，未找到返回 null
     */
    public NurseInfo resolveByResponsibleNurse(String responsibleNurseName) {
        if (responsibleNurseName == null || responsibleNurseName.trim().isEmpty()) {
            return null;
        }
        String name = responsibleNurseName.trim();

        // 按 trueName 查询 account 集合
        try {
            Query query = new Query(Criteria.where("trueName").is(name)).limit(1);
            Document account = mongoTemplate.findOne(query, Document.class, "account");
            if (account != null) {
                String username = account.getString("username");
                if (username != null && !username.trim().isEmpty()) {
                    return NurseInfo.builder()
                            .nurseId(username.trim())
                            .nurseName(name)
                            .source("responsibleNurse")
                            .reasonCode(null)
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("NurseResolver: failed to query account by trueName={}: {}", name, e.getMessage());
        }

        return null;
    }

    // ------------------------------------------------------------------
    // param_Yishi lookup
    // ------------------------------------------------------------------

    /**
     * Query bedside for code=param_Yishi in the given time window, return editUser string or null.
     */
    private String findEditUserFromParamYishi(String pid, Date startTime, Date endTime) {
        Query query = new Query(Criteria.where("code").is("param_Yishi")
                .and("pid").is(pid)
                .and("valid").ne(false).ne(0)
                .andOperator(
                        Criteria.where("time").gte(startTime),
                        Criteria.where("time").lt(endTime)
                ));
        query.with(Sort.by(Sort.Order.desc("editTime")));
        query.limit(1);

        Document doc = mongoTemplate.findOne(query, Document.class, "bedside");
        if (doc == null) {
            return null;
        }
        // 二次过滤：排除 valid=false 的作废记录
        Object valid = doc.get("valid");
        if (Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid)) {
            return null;
        }
        return extractEditUser(doc);
    }

    // ------------------------------------------------------------------
    // editUser extraction (handles ObjectId, String, null)
    // ------------------------------------------------------------------

    /**
     * Safely extract editUser from a document.
     * Returns null if missing, empty, or invalid.
     */
    String extractEditUser(Document doc) {
        if (doc == null) {
            return null;
        }
        Object raw = doc.get("editUser");
        if (raw == null) {
            return null;
        }

        String idStr;
        if (raw instanceof ObjectId) {
            idStr = raw.toString();
        } else if (raw instanceof String) {
            idStr = ((String) raw).trim();
        } else {
            idStr = raw.toString().trim();
        }

        if (idStr.isEmpty()) {
            return null;
        }

        // Validate ObjectId format (24 hex chars)
        if (!ObjectId.isValid(idStr)) {
            log.warn("NurseResolver: invalid ObjectId format for editUser: {}", idStr);
            return null;
        }
        return idStr;
    }

    // ------------------------------------------------------------------
    // Account lookup with cache
    // ------------------------------------------------------------------

    /**
     * Look up account._id = editUser and return trueName.
     * Uses a bounded TTL cache; never caches null/error results.
     */
    private String lookupTrueName(String editUser) {
        AccountInfo info = lookupAccountInfo(editUser);
        return info != null ? info.trueName : null;
    }

    /**
     * Look up account._id = editUser and return both username and trueName.
     * Uses a bounded TTL cache; never caches null/error results.
     */
    AccountInfo lookupAccountInfo(String editUser) {
        // Check cache (reuse trueName cache for hit)
        CacheEntry cached = accountCache.get(editUser);
        if (cached != null && !cached.isExpired()) {
            return new AccountInfo(cached.username, cached.trueName);
        }

        // Query account collection
        AccountInfo info = queryAccountInfo(editUser);

        // Cache only non-null results
        if (info != null) {
            putCache(editUser, info.username, info.trueName);
        }

        return info;
    }

    /**
     * Query the account collection for _id = accountId, return AccountInfo or null.
     */
    private AccountInfo queryAccountInfo(String accountId) {
        try {
            Query query = new Query(Criteria.where("_id").is(new ObjectId(accountId)));
            Document account = mongoTemplate.findOne(query, Document.class, "account");
            if (account == null) {
                return null;
            }
            String username = account.getString("username");
            String trueName = account.getString("trueName");
            if (trueName == null || trueName.trim().isEmpty()) {
                return null;
            }
            return new AccountInfo(
                    username != null ? username.trim() : "",
                    trueName.trim());
        } catch (Exception e) {
            log.warn("NurseResolver: failed to query account for editUser={}: {}", accountId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Cache management
    // ------------------------------------------------------------------

    private void putCache(String key, String username, String trueName) {
        evictExpired();

        if (accountCache.size() >= MAX_CACHE_SIZE) {
            evictOldest();
        }

        accountCache.put(key, new CacheEntry(username, trueName, System.currentTimeMillis()));
    }

    /** Remove all entries older than CACHE_TTL_MS. */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        accountCache.entrySet().removeIf(e -> (now - e.getValue().timestamp) > CACHE_TTL_MS);
    }

    /** Remove the oldest entry when cache is full. */
    private void evictOldest() {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, CacheEntry> entry : accountCache.entrySet()) {
            if (entry.getValue().timestamp < oldestTime) {
                oldestTime = entry.getValue().timestamp;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            accountCache.remove(oldestKey);
        }
    }

    /** Visible for testing */
    int getCacheSize() {
        return accountCache.size();
    }

    /** Visible for testing - clear cache between tests */
    void clearCache() {
        accountCache.clear();
    }

    // ------------------------------------------------------------------
    // Inner class
    // ------------------------------------------------------------------

    static class CacheEntry {
        final String username;
        final String trueName;
        final long timestamp;

        CacheEntry(String username, String trueName, long timestamp) {
            this.username = username;
            this.trueName = trueName;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_TTL_MS;
        }
    }

    /** Account lookup result */
    static class AccountInfo {
        final String username;
        final String trueName;

        AccountInfo(String username, String trueName) {
            this.username = username;
            this.trueName = trueName;
        }
    }
}
