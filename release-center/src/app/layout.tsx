import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "跑伴 RunMate · 发布中心",
  description: "Modern Next.js scaffold optimized for AI-powered development with Z.ai. Built with TypeScript, Tailwind CSS, and shadcn/ui.",
  keywords: ["跑伴", "RunMate", "DUT", "大连理工", "健康长跑", "APK", "发布中心"],
  authors: [{ name: "DUT-RunMate" }],
  icons: {
    icon: "https://z-cdn.chatglm.cn/z-ai/static/logo.svg",
  },
  openGraph: {
    title: "跑伴 RunMate · 发布中心",
    description: "大工健康长跑打卡提醒 App 发布中心",
    url: "https://dut-runmate.space-z.ai",
    siteName: "跑伴 RunMate",
    type: "website",
  },
  twitter: {
    card: "summary_large_image",
    title: "跑伴 RunMate · 发布中心",
    description: "大工健康长跑打卡提醒 App 发布中心",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased bg-background text-foreground`}
      >
        {children}
        <Toaster />
      </body>
    </html>
  );
}
