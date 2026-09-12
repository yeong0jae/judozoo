# 표준 caddy 이미지에는 DNS 프로바이더 모듈이 없다. DNS-01 챌린지를 쓰려면
# xcaddy로 직접 끼워 빌드해야 한다.
#
# DNS-01을 쓰는 이유: HTTP-01은 Let's Encrypt 검증 서버가 임의 IP에서 오기 때문에
# :80을 0.0.0.0/0에 열어야 한다. 방화벽 IP 화이트리스트를 유지하려면 DNS-01뿐이다.
FROM caddy:2-builder AS build
RUN xcaddy build --with github.com/caddy-dns/cloudflare

FROM caddy:2
COPY --from=build /usr/bin/caddy /usr/bin/caddy
COPY Caddyfile /etc/caddy/Caddyfile
