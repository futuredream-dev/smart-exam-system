FROM node:22-bookworm-slim AS build
WORKDIR /build
RUN npm install -g pnpm@9.15.9
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY index.html vite.config.js ./
COPY src ./src
COPY public ./public
ENV VITE_API_BASE_URL=/api
RUN pnpm build

FROM nginx:1.28-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
