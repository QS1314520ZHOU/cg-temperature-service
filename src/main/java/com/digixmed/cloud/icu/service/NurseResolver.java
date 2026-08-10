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
            String trueName = lookupTrueName(editUserFromParamYishi);
            if (trueName != null) {
                return NurseInfo.builder()
                        .nurseId("dba")
                        .nurseName(trueName)
                        .source("param_Yishi")
                        .reasonCode(null)
                        .build();
            }
        }

        // Step 2: fallback to source record editUser
        String editUserFromRecord = extractEditUser(sourceRecord);
        if (editUserFromRecord != null) {
            String trueName = lookupTrueName(editUserFromRecord);
            if (trueName != null) {
                return NurseInfo.builder()
                        .nurseId("dba")
                        .nurseName(trueName)
                        .source("source_record")
                        .reasonCode(null)
                        .build();
            }
        }

        // Step 3: not found
        return NurseInfo.builder()
                .nurseId("dba")
                .nurseName("")
                .source("not_found")
                .reasonCode("NURSE_NOT_FOUND")
                .build();
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
                .and("valid").is(true)
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
        // Check cache
        CacheEntry cached = accountCache.get(editUser);
        if (cached != null && !cached.isExpired()) {
            return cached.trueName;
        }

        // Query account collection
        String trueName = queryAccountTrueName(editUser);

        // Cache only non-null results
        if (trueName != null) {
            putCache(editUser, trueName);
        }

        return trueName;
    }

    /**
     * Query the account collection for _id = accountId, return trueName or null.
     */
    private String queryAccountTrueName(String accountId) {
        try {
            Query query = new Query(Criteria.where("_id").is(new ObjectId(accountId)));
            Document account = mongoTemplate.findOne(query, Document.class, "account");
            if (account == null) {
                return null;
            }
            Object trueNameObj = account.get("trueName");
            if (trueNameObj == null) {
                return null;
            }
            String trueName = trueNameObj.toString().trim();
            return trueName.isEmpty() ? null : trueName;
        } catch (Exception e) {
            log.warn("NurseResolver: failed to query account for editUser={}: {}", accountId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Cache management
    // ------------------------------------------------------------------

    private void putCache(String key, String trueName) {
        evictExpired();

        if (accountCache.size() >= MAX_CACHE_SIZE) {
            evictOldest();
        }

        accountCache.put(key, new CacheEntry(trueName, System.currentTimeMillis()));
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
        final String trueName;
        final long timestamp;

        CacheEntry(String trueName, long timestamp) {
            this.trueName = trueName;
            this.timestamp = timestamp;
        }

        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > CACHE_TTL_MS;
        }
    }
}
