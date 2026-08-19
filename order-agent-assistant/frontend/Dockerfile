# 阶段一：Node 构建
FROM node:20-alpine AS build
WORKDIR /app
# 先只拷贝清单，充分利用 Docker 层缓存（改代码不用重装依赖）
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# 阶段二：nginx 托管 + 反代
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
# 反代规则：/api → order-system，/query /approve → agent（同源，无 CORS）
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8082
