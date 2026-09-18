import { Link } from "react-router-dom";
import Wordmark from "./Wordmark";

export default function Footer() {
  return (
    <footer className="border-t border-zinc-800 mt-auto">
      <div className="max-w-[110rem] mx-auto w-full px-4 sm:px-6 py-5 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-zinc-600">
        <Wordmark size={16} />
        <span>
          제공되는 정보는 투자 권유가 아니며, 투자 판단과 책임은 이용자 본인에게 있습니다.
        </span>
        <span className="flex-1" />
        <a href="mailto:judozooweb@gmail.com" className="hover:text-zinc-400">
          문의 judozooweb@gmail.com
        </a>
        <Link to="/terms" className="hover:text-zinc-400">
          이용약관
        </Link>
        <Link to="/privacy" className="hover:text-zinc-400">
          개인정보 처리방침
        </Link>
      </div>
    </footer>
  );
}
