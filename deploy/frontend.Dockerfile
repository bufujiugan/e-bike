FROM node:22-alpine AS build
ARG APP=bike-web
WORKDIR /build
RUN npm install -g pnpm@9.15.9
COPY ${APP}/package.json ${APP}/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY ${APP}/ ./
RUN pnpm build

FROM nginx:1.28-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
