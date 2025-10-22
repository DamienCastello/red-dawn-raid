#!/bin/sh
set -eu
: "${API_BASE_URL:=/api}"
mkdir -p /usr/share/nginx/html/assets
cat > /usr/share/nginx/html/assets/env.js <<EOF
window.__env = { API_BASE_URL: "${API_BASE_URL}" };
EOF
exec nginx -g "daemon off;"
