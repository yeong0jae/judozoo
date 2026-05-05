import { useEffect } from "react";
import { useNotifications } from "../notifications/notifications";
const BASE_TITLE = "AT 자동매매";
// 미확인 알림이 있으면 탭 제목 앞에 카운트 부착.
export function useTabTitle() {
    const { unreadCount } = useNotifications();
    useEffect(() => {
        document.title =
            unreadCount > 0 ? `(${unreadCount}) ${BASE_TITLE}` : BASE_TITLE;
    }, [unreadCount]);
}
