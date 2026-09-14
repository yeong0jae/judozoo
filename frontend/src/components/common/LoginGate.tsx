import { motion } from "motion/react";
import GoogleLoginButton from "./GoogleLoginButton";

/** 관문 뒤 화면에서 미로그인일 때 보여준다. 화면을 숨기는 게 아니라 이유와 다음 행동을 준다. */
export default function LoginGate({ title, description }: { title: string; description: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="flex flex-col items-center justify-center gap-4 py-24 text-center"
    >
      <div className="space-y-1.5">
        <h2 className="text-lg font-semibold text-zinc-100">{title}</h2>
        <p className="text-sm text-zinc-500 max-w-md">{description}</p>
      </div>
      <GoogleLoginButton />
    </motion.div>
  );
}
