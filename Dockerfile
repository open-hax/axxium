FROM node:22-alpine

WORKDIR /app

# Copy built application
COPY dist/ ./dist/
COPY resources/ ./resources/
COPY package.prod.json ./package.json

# Install only production dependencies
RUN npm install --ignore-scripts --production

# Expose port
EXPOSE 8787

# Set environment
ENV NODE_ENV=production

# Start the application
CMD ["node", "dist/server.js"]
