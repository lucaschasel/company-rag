FROM node:20-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY rate-limit.conf /etc/nginx/conf.d/00-rate-limit.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
