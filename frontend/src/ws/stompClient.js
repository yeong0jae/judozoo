import { Client } from "@stomp/stompjs";
let instance = null;
export function getStompClient() {
    if (!instance) {
        const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        instance = new Client({
            brokerURL: `${wsProtocol}//${window.location.host}/ws`,
            reconnectDelay: 5000,
            heartbeatIncoming: 10_000,
            heartbeatOutgoing: 10_000,
        });
    }
    return instance;
}
