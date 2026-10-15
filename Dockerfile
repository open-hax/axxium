FROM node:22-alpine

WORKDIR /app

COPY package.prod.json ./package.json

# The CI build supplies compiled ClojureScript. Keep the runtime image free of
# the compiler and its Java dependencies.
RUN npm install --ignore-scripts --omit=dev

COPY dist/ ./dist/
COPY resources/ ./resources/
COPY scripts/bootstrap-local-admin.mjs ./scripts/bootstrap-local-admin.mjs

USER node

# Expose port
EXPOSE 8787

ENV NODE_ENV=production

# Start the application
CMD ["node", "dist/server.js"]
