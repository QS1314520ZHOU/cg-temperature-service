package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.NurseInfo;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NurseResolver unit tests.
 * Uses Mockito to mock MongoTemplate so no real MongoDB is needed.
 */
@ExtendWith(MockitoExtension.class)
class NurseResolverTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @InjectMocks
    private NurseResolver nurseResolver;

    private String pid;
    private Date startTime;
    private Date endTime;

    @BeforeEach
    void setUp() {
        nurseResolver.clearCache();
        pid = new ObjectId().toString();
        startTime = new Date(0L);
        endTime = new Date(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Test 1: param_Yishi.editUser resolves to account.trueName
    // ------------------------------------------------------------------
    @Test
    void resolve_paramYishi_found_resolvesToAccountTrueName() {
        String editUserId = new ObjectId().toString();

        // Mock bedside query for param_Yishi -> returns editUser
        Document paramYishiDoc = new Document("editUser", editUserId);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        // Mock account query -> returns trueName
        Document accountDoc = new Document("trueName", "张三");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(accountDoc);

        NurseInfo result = nurseResolver.resolve(new Document(), pid, startTime, endTime);

        assertEquals("dba", result.getNurseId());
        assertEquals("张三", result.getNurseName());
        assertEquals("param_Yishi", result.getSource());
        assertNull(result.getReasonCode());
    }

    // ------------------------------------------------------------------
    // Test 2: Fallback to source record editUser when no param_Yishi
    // ------------------------------------------------------------------
    @Test
    void resolve_noParamYishi_fallsBackToSourceRecord() {
        String editUserId = new ObjectId().toString();

        // First call (param_Yishi query) -> null (not found)
        // Second call (account query) -> returns trueName
        Document accountDoc = new Document("trueName", "李四");

        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(null); // no param_Yishi
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(accountDoc);

        // Source record has editUser
        Document sourceRecord = new Document("editUser", editUserId);

        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("dba", result.getNurseId());
        assertEquals("李四", result.getNurseName());
        assertEquals("source_record", result.getSource());
        assertNull(result.getReasonCode());
    }

    // ------------------------------------------------------------------
    // Test 3: Returns empty name when account not found
    // ------------------------------------------------------------------
    @Test
    void resolve_accountNotFound_returnsNotFound() {
        String editUserId = new ObjectId().toString();

        // No param_Yishi
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(null);

        // Account not found
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(null);

        Document sourceRecord = new Document("editUser", editUserId);

        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("dba", result.getNurseId());
        assertEquals("", result.getNurseName());
        assertEquals("not_found", result.getSource());
        assertEquals("NURSE_NOT_FOUND", result.getReasonCode());
    }

    // ------------------------------------------------------------------
    // Test 4: recordNurseId always "dba"
    // ------------------------------------------------------------------
    @Test
    void resolve_alwaysReturnsDbaNurseId() {
        // param_Yishi found
        String editUserId = new ObjectId().toString();
        Document paramYishiDoc = new Document("editUser", editUserId);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);
        Document accountDoc = new Document("trueName", "王五");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(accountDoc);

        NurseInfo result = nurseResolver.resolve(new Document(), pid, startTime, endTime);
        assertEquals("dba", result.getNurseId());

        // Also verify not_found case
        nurseResolver.clearCache();
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(null);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(null);

        Document sourceRecord = new Document("editUser", editUserId);
        NurseInfo result2 = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);
        assertEquals("dba", result2.getNurseId());
    }

    // ------------------------------------------------------------------
    // Test 5: Invalid ObjectId doesn't crash task
    // ------------------------------------------------------------------
    @Test
    void resolve_invalidObjectId_returnsNotFound_noException() {
        // param_Yishi with invalid editUser
        Document paramYishiDoc = new Document("editUser", "not-a-valid-objectid");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        // Source record also with invalid editUser
        Document sourceRecord = new Document("editUser", "bad-id");

        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("dba", result.getNurseId());
        assertEquals("", result.getNurseName());
        assertEquals("not_found", result.getSource());
        assertEquals("NURSE_NOT_FOUND", result.getReasonCode());
    }

    // ------------------------------------------------------------------
    // Test 5b: null editUser doesn't crash
    // ------------------------------------------------------------------
    @Test
    void resolve_nullEditUser_returnsNotFound() {
        Document paramYishiDoc = new Document("editUser", null);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        Document sourceRecord = new Document(); // no editUser at all

        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("not_found", result.getSource());
        assertEquals("NURSE_NOT_FOUND", result.getReasonCode());
    }

    // ------------------------------------------------------------------
    // Test 5c: empty string editUser
    // ------------------------------------------------------------------
    @Test
    void resolve_emptyEditUser_returnsNotFound() {
        Document paramYishiDoc = new Document("editUser", "");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        Document sourceRecord = new Document("editUser", "   ");

        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("not_found", result.getSource());
    }

    // ------------------------------------------------------------------
    // Test 5d: editUser as ObjectId BSON type
    // ------------------------------------------------------------------
    @Test
    void resolve_objectIdEditUser_resolves() {
        ObjectId oid = new ObjectId();
        String oidStr = oid.toString();

        Document paramYishiDoc = new Document("editUser", oid);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        Document accountDoc = new Document("trueName", "赵六");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(accountDoc);

        NurseInfo result = nurseResolver.resolve(new Document(), pid, startTime, endTime);

        assertEquals("赵六", result.getNurseName());
        assertEquals("param_Yishi", result.getSource());
    }

    // ------------------------------------------------------------------
    // Test 5e: Account trueName is null or empty
    // ------------------------------------------------------------------
    @Test
    void resolve_accountTrueNameNull_returnsNotFound() {
        String editUserId = new ObjectId().toString();

        Document paramYishiDoc = new Document("editUser", editUserId);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        // Account exists but trueName is null
        Document accountDoc = new Document("trueName", null);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(accountDoc);

        // Source record also has same editUser, will also fail trueName check
        Document sourceRecord = new Document("editUser", editUserId);

        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("not_found", result.getSource());
        assertEquals("NURSE_NOT_FOUND", result.getReasonCode());
    }

    @Test
    void resolve_accountTrueNameEmpty_returnsNotFound() {
        String editUserId = new ObjectId().toString();

        Document paramYishiDoc = new Document("editUser", editUserId);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        Document accountDoc = new Document("trueName", "   ");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(accountDoc);

        Document sourceRecord = new Document("editUser", editUserId);

        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("not_found", result.getSource());
    }

    // ------------------------------------------------------------------
    // Test 6: Cache hit works
    // ------------------------------------------------------------------
    @Test
    void resolve_cacheHit_doesNotQueryAccountTwice() {
        String editUserId = new ObjectId().toString();

        Document paramYishiDoc = new Document("editUser", editUserId);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        Document accountDoc = new Document("trueName", "缓存测试");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(accountDoc);

        // First call - should query account
        NurseInfo result1 = nurseResolver.resolve(new Document(), pid, startTime, endTime);
        assertEquals("缓存测试", result1.getNurseName());

        // Second call with same editUser - should hit cache, not query account again
        // param_Yishi returns null this time, but source record has same editUser
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(null);
        Document sourceRecord = new Document("editUser", editUserId);

        NurseInfo result2 = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("缓存测试", result2.getNurseName());
        assertEquals("source_record", result2.getSource());

        // Account should have been queried only once (first call)
        verify(mongoTemplate, times(1))
                .findOne(any(Query.class), eq(Document.class), eq("account"));
    }

    // ------------------------------------------------------------------
    // Test 7: Cache doesn't grow infinitely (test eviction)
    // ------------------------------------------------------------------
    @Test
    void resolve_cacheEviction_doesNotExceedMaxSize() {
        // Insert MAX_CACHE_SIZE + 100 entries (simulating by directly manipulating cache
        // is not possible from outside, so we call resolve repeatedly with different editUsers)
        // We'll insert enough to exceed MAX_CACHE_SIZE and verify eviction.

        // First, fill cache by resolving many unique editUsers
        for (int i = 0; i < 1005; i++) {
            String editUserId = new ObjectId().toString();
            Document sourceRecord = new Document("editUser", editUserId);

            // param_Yishi not found
            when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                    .thenReturn(null);

            Document accountDoc = new Document("trueName", "Nurse_" + i);
            when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                    .thenReturn(accountDoc);

            nurseResolver.resolve(sourceRecord, pid, startTime, endTime);
        }

        // Cache should not exceed MAX_CACHE_SIZE
        assertTrue(nurseResolver.getCacheSize() <= 1000,
                "Cache size " + nurseResolver.getCacheSize() + " should not exceed 1000");
    }

    // ------------------------------------------------------------------
    // Test: editUser from param_Yishi is empty, falls through to source
    // ------------------------------------------------------------------
    @Test
    void resolve_paramYishiEmptyEditUser_fallsBackToSource() {
        String editUserId = new ObjectId().toString();

        // param_Yishi found but editUser is empty
        Document paramYishiDoc = new Document("editUser", "");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        // Source record has valid editUser
        Document accountDoc = new Document("trueName", "fallback Nurse");
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenReturn(accountDoc);

        Document sourceRecord = new Document("editUser", editUserId);

        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("fallback Nurse", result.getNurseName());
        assertEquals("source_record", result.getSource());
    }

    // ------------------------------------------------------------------
    // Test: Account query throws exception (should not crash)
    // ------------------------------------------------------------------
    @Test
    void resolve_accountQueryThrowsException_returnsNotFound() {
        String editUserId = new ObjectId().toString();

        Document paramYishiDoc = new Document("editUser", editUserId);
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(paramYishiDoc);

        // Account query throws
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("account")))
                .thenThrow(new RuntimeException("DB connection failed"));

        Document sourceRecord = new Document("editUser", editUserId);

        // Should not throw
        NurseInfo result = nurseResolver.resolve(sourceRecord, pid, startTime, endTime);

        assertEquals("not_found", result.getSource());
        assertEquals("NURSE_NOT_FOUND", result.getReasonCode());
    }
}
