FROM node:22-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
ARG XNLP_VERSION=0.4.0
LABEL org.opencontainers.image.title="X-NLP Frontend" \
      org.opencontainers.image.version="${XNLP_VERSION}"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
