# Council — Quick Reference & Next Steps

## 🎯 Current Issues to Fix

### Issue 1: All Providers Failing

```json
Response: {
  "traceId": "...",
  "judgeReason": "All providers failed: [gemini, deepseek, claude]"
}
```

**Diagnosis Steps:**

1. **Verify API keys are set:**
   ```bash
   echo $DEEPSEEK_API_KEY
   echo $GEMINI_API_KEY
   echo $OPENROUTER_API_KEY
   # etc.
   ```

2. **Check which providers are configured in application.yml:**
   - Look at `council.providers.*` section
   - Verify `enabled: true` for desired providers

3. **Enable detailed logging:**
   Edit `src/main/resources/application.yml`:
   ```yaml
   logging:
     level:
       com.council: DEBUG
       com.council.provider: DEBUG
   ```

4. **Test a single provider directly:**
   
   Create `TestProviderDirectly.java`:
   ```java
   @SpringBootTest
   class TestProviderDirectly {
       @Autowired ProviderRegistry registry;
       
       @Test
       void testDeepSeek() {
           LlmAdapter adapter = registry.get("deepseek");
           DraftRequest req = new DraftRequest(
               UUID.randomUUID().toString(),
               "What is 2+2?",
               "draft"
           );
           DraftResult result = adapter.generateDraft(req);
           System.out.println(result);
       }
   }
   ```
   
   Run: `mvn test -Dtest=TestProviderDirectly`

5. **Check ProviderCallExecutor logs:**
   - Look for `[provider=...] ERROR` in logs
   - Check if request is being sent
   - Check response status codes

### Issue 2: Java 21 Compilation Error

```
error: release version 21 not supported
```

**Fix:**

```bash
# Check Java version
java -version

# Should show: "java version "21.0.x" ..."

# If not Java 21:
# 1. Download Java 21 LTS from oracle.com or openjdk.net
# 2. Set JAVA_HOME:
export JAVA_HOME=/path/to/java21

# 3. Verify Maven uses Java 21:
mvn -v

# 4. Clean and compile:
mvn clean compile
```

### Issue 3: PostgreSQL Connection Refused

```
Connection to localhost:5432 refused
```

**Fix:**

```bash
# Start PostgreSQL in Docker:
docker run -d --name council-db \
  -e POSTGRES_DB=council \
  -e POSTGRES_USER=council \
  -e POSTGRES_PASSWORD=council \
  -p 5432:5432 postgres:16-alpine

# Verify it's running:
docker ps | grep council-db

# Test connection:
docker exec council-db psql -U council -d council -c "SELECT 1"
```

---

## 📋 What to Test Next

### 1. Verify API Key Configuration

Create `.env` file in project root:

```bash
# .env
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxx
GEMINI_API_KEY=AIzaxxxxxxxxxxxxxxx
OPENROUTER_API_KEY=sk-or-xxxxxxxx
GROQ_API_KEY=gsk-xxxxxxxx
TOGETHER_API_KEY=xxxxx
MISTRAL_API_KEY=xxxx
KIMI_API_KEY=sk-xxxxxx
CLAUDE_API_KEY=sk-ant-xxxxxx (optional)

DB_USERNAME=council
DB_PASSWORD=council
```

### 2. Start Application

```bash
# Option A: Maven
mvn clean spring-boot:run

# Option B: Docker Compose
docker compose up --build

# Option C: Build JAR first
mvn clean package -DskipTests
java -jar target/council-0.1.0-SNAPSHOT.jar
```

### 3. Test Single Provider

```bash
# Get provider status
curl http://localhost:8080/api/v1/providers/status | jq .

# Should see each provider with:
# - enabled: true/false
# - coolingDown: true/false
# - lastSuccess: timestamp
# - lastFailure: error message
```

### 4. Test Reasoning Endpoint

```bash
# Simple test
curl -X POST http://localhost:8080/api/v1/reason \
  -H "Content-Type: application/json" \
  -d '{"query":"What is 2+2?"}'

# Should return:
{
  "traceId": "...",
  "finalAnswer": "4",
  "judgeReason": "Ranking: provider1=0.85, provider2=0.75. Winner selected.",
  "usedProviders": ["provider1", "provider2"],
  "failedProviders": [],
  "confidence": 0.85
}
```

### 5. Check Trace

```bash
# Get the trace ID from previous response
TRACE_ID="<from-response>"

# Retrieve trace
curl http://localhost:8080/api/v1/traces/$TRACE_ID | jq .

# Get debug view (detailed)
curl http://localhost:8080/api/v1/traces/$TRACE_ID/debug | jq .
```

---

## 🔍 Debugging Provider Issues

### Enable Verbose Logging

Add to `application.yml`:

```yaml
logging:
  level:
    com.council: DEBUG
    com.council.provider: DEBUG
    com.council.resilience: DEBUG
    org.springframework.web.client: DEBUG
```

### Check Request/Response in Code

Add breakpoint in `AbstractLlmAdapter.generateDraft()`:

```java
// Look for:
log.info("[{} draft] Request: {}", provider, requestBody);
log.info("[{} draft] Response status: {}", provider, response.getStatusCode());
log.info("[{} draft] Response body: {}", provider, response.getBody());
```

### Test with curl (If API supports direct REST)

Example for DeepSeek:

```bash
curl -X POST https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "What is 2+2?"}],
    "temperature": 1,
    "max_tokens": 4096
  }' | jq .
```

---

## 📊 Performance Baseline

Once working, these are expected metrics:

| Metric | Expected | Range |
|--------|----------|-------|
| Draft phase latency | 10-30s | per 3 providers |
| Critic latency | 5-10s | single provider |
| Judge latency | <100ms | pure Java |
| Total latency | 15-40s | end-to-end |
| Success rate | >80% | per provider |
| P99 latency | <60s | end-to-end |

---

## 🚀 Immediate Action Items

### Day 1: Get One Provider Working

1. **Check Java 21 installation**
   ```bash
   java -version  # must show 21.x.x
   ```

2. **Start PostgreSQL**
   ```bash
   docker run -d --name council-db \
     -e POSTGRES_DB=council \
     -e POSTGRES_USER=council \
     -e POSTGRES_PASSWORD=council \
     -p 5432:5432 postgres:16-alpine
   ```

3. **Set API keys in `.env`**
   - Get keys from OpenRouter (universal provider that works well)
   - Or use your preferred provider

4. **Start application**
   ```bash
   mvn clean spring-boot:run
   ```

5. **Test one provider**
   ```bash
   curl http://localhost:8080/api/v1/providers/status
   # See which providers show enabled=true
   ```

### Day 2: Diagnostic Testing

1. **Enable DEBUG logging**
2. **Test `/api/v1/reason` endpoint**
3. **Capture full logs**
4. **Check provider adapter code** for request format issues
5. **Compare against provider API documentation**

### Day 3: Fix & Validate

1. **Fix any request format issues** in adapters
2. **Test each provider independently**
3. **Run integration tests**
4. **Performance test** with multiple concurrent requests

---

## 📁 Key Files to Check

| File | Purpose |
|------|---------|
| `src/main/resources/application.yml` | Provider config |
| `src/main/java/com/council/provider/*/` | Adapter implementations |
| `src/main/java/com/council/orchestrator/ReasoningOrchestrator.java` | Main pipeline |
| `src/main/java/com/council/provider/ProviderCallExecutor.java` | Retry logic |
| `.env` | API keys (LOCAL ONLY) |
| `docker-compose.yml` | Container setup |
| `src/main/resources/db/migration/` | Database schema |

---

## 🎓 Understanding Provider Selection

```
1. User submits query
   ↓
2. PromptClassifier identifies task type
   (SYSTEM_DESIGN, DEBUGGING, CODING, etc.)
   ↓
3. ProviderSelectionStrategy selects 3-5 providers based on:
   - enabled: true
   - NOT in cooldown
   - priority (1 = highest)
   - task type matching
   - max concurrency available
   ↓
4. All selected providers called in parallel
   ↓
5. Successful drafts → Critic
   ↓
6. Critic result + drafts → Judge
   ↓
7. Judge selects winner using task-aware weights
   ↓
8. If confidence too low → escalate to Gemini/Claude
```

---

## 🛠️ Troubleshooting Checklist

- [ ] Java version is 21.x
- [ ] PostgreSQL is running on 5432
- [ ] Database exists: `psql -U council -d council`
- [ ] API keys are set: `echo $DEEPSEEK_API_KEY`
- [ ] Application starts without errors: `mvn spring-boot:run`
- [ ] Health endpoint responds: `curl http://localhost:8080/api/v1/health`
- [ ] Provider status shows providers: `curl http://localhost:8080/api/v1/providers/status`
- [ ] Can submit reasoning request: `curl -X POST ... /api/v1/reason`
- [ ] Response includes valid traceId
- [ ] Trace can be retrieved: `curl /api/v1/traces/{traceId}`

---

## 📞 Getting Help

**For API key issues:**
- Check provider documentation for key format
- Ensure key is valid and not expired
- Try provider's own API testing tool first

**For compilation issues:**
- Run `mvn clean`
- Check Maven version: `mvn -v` (should be 3.9+)
- Set `JAVA_HOME` explicitly

**For database issues:**
- Check PostgreSQL logs: `docker logs council-db`
- Verify migrations ran: `SELECT * FROM flyway_schema_history;`
- Check data source config in `application.yml`

**For provider connectivity:**
- Check provider status endpoint
- Look at application logs for error messages
- Enable DEBUG logging
- Check request/response in network tools

---

**Last Updated:** April 16, 2026  
**Status:** Ready for Testing  
**Next Action:** Fix provider connectivity + compile Java 21

