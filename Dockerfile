FROM node:22-alpine

WORKDIR /app

# Copy built application
COPY dist/ ./dist/
COPY resources/ ./resources/
COPY package.json package-lock.json ./

# Install only production dependencies
RUN npm ci --ignore-scripts --omit=dev

USER node

# Expose port
EXPOSE 8787

# Set environment
ENV NODE_ENV=production

# Start the application
CMD ["node", "dist/server.js"]
