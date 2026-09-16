# X-NLP Helm Chart

This chart deploys the X-NLP Spring Boot server and optional React/Nginx workbench.
The chart assumes the configured database and Ollama endpoint are reachable from
Kubernetes; use `server.database.url` and `server.env.ollamaBaseUrl` to point at
managed services or separate releases.

## Install

```bash
helm lint deploy/helm/xnlp
helm template xnlp deploy/helm/xnlp --set server.database.password='change-me'
helm upgrade --install xnlp deploy/helm/xnlp \
  --set server.database.url='jdbc:postgresql://postgres:5432/xnlp' \
  --set server.database.username='xnlp' \
  --set server.database.password='change-me' \
  --set server.env.profile='postgres' \
  --set server.env.aiModelChat='ollama' \
  --set server.env.ollamaBaseUrl='http://ollama:11434'
```

For production, provide secrets through a values file managed by your secret
store, enable ingress/TLS, set resource requests and limits, and use an external
MySQL/PostgreSQL database. The server migration runner applies versioned schema
changes on startup and the server PVC stores H2 data and waste evidence when the
H2 profile is selected.

## Authentication

Set `server.security.mode` to `API_KEY`, `JWT`, or `HYBRID`. API keys stay in
the generated Kubernetes Secret. JWT metadata is non-secret deployment
configuration and should include at least an issuer and audience:

```bash
helm upgrade --install xnlp deploy/helm/xnlp \
  --set server.security.mode=JWT \
  --set server.security.jwt.issuerUri='https://id.example.com/realms/xnlp' \
  --set server.security.jwt.jwkSetUri='https://id.example.com/realms/xnlp/protocol/openid-connect/certs' \
  --set server.security.jwt.audience='xnlp-api'
```

`server.security.enabled=true` remains a compatibility shortcut for API Key
mode when `server.security.mode` is empty.
