FROM nginx:stable-alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY frontend/dist /usr/share/nginx/html
COPY deploy/vms.conf.template /etc/nginx/templates/vms.conf.template
COPY deploy/proxy.conf /etc/nginx/vms-proxy.conf
