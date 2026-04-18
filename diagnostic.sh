#!/bin/bash
# Diagnostic script to check provider configuration and API keys

echo "================================================"
echo "Council Provider Configuration Diagnostic"
echo "================================================"
echo ""

# Check environment variables
echo "1. Checking Environment Variables..."
echo "================================================"

keys=(
  "DEEPSEEK_API_KEY"
  "GEMINI_API_KEY"
  "CLAUDE_API_KEY"
  "OPENROUTER_API_KEY"
  "GROQ_API_KEY"
  "TOGETHER_API_KEY"
  "MISTRAL_API_KEY"
  "KIMI_API_KEY"
  "HUGGINGFACE_API_KEY"
)

for key in "${keys[@]}"; do
  value=${!key}
  if [ -z "$value" ]; then
    echo "❌ $key: NOT SET"
  else
    # Show first 10 chars + dots for security
    prefix=$(echo "$value" | cut -c1-10)
    echo "✅ $key: ${prefix}..."
  fi
done

echo ""
echo "2. Application Status..."
echo "================================================"

# Check if app is running
if curl -s http://localhost:8080/api/v1/health > /dev/null 2>&1; then
  echo "✅ Application is running on localhost:8080"

  # Get health status
  echo ""
  echo "3. Health Endpoint..."
  echo "================================================"
  curl -s http://localhost:8080/api/v1/health | jq '.'

  # Get provider status
  echo ""
  echo "4. Provider Status..."
  echo "================================================"
  curl -s http://localhost:8080/api/v1/providers/status | jq '.[] | {provider: .provider, enabled: .enabled, coolingDown: .coolingDown, model: .model}'

  # Get metrics
  echo ""
  echo "5. Application Metrics..."
  echo "================================================"
  curl -s http://localhost:8080/api/v1/metrics | jq '.providers'

else
  echo "❌ Application is NOT running on localhost:8080"
  echo "   Start it with: mvn spring-boot:run"
fi

echo ""
echo "================================================"
echo "Diagnostics Complete"
echo "================================================"

