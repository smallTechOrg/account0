const backend = process.env.ACCOUNT0_BACKEND_URL || "http://127.0.0.1:8710";
/** @type {import('next').NextConfig} */
export default {
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${backend}/api/:path*` },
      { source: "/health", destination: `${backend}/health` },
    ];
  },
};
