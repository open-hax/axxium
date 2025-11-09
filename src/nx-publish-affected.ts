#!/usr/bin/env bun

import { execSync } from 'child_process'
import { readFileSync } from 'fs'
import { join } from 'path'

interface PackageJson {
  name?: string
  version?: string
  private?: boolean
}

function getAffectedProjects(): string[] {
  try {
    const output = execSync('nx show projects --affected', { encoding: 'utf8' })
    return output.trim().split('\n').filter(Boolean)
  } catch (error) {
    console.error('Error getting affected projects:', error)
    return []
  }
}

function buildProject(project: string): boolean {
  try {
    console.log(`🔨 Building ${project}...`)
    execSync(`nx build ${project}`, { stdio: 'inherit' })
    console.log(`✅ Build succeeded for ${project}`)
    return true
  } catch (error) {
    console.error(`❌ Build failed for ${project}:`, error)
    return false
  }
}

function publishProject(project: string): boolean {
  const packageJsonPath = join(process.cwd(), 'dist', 'packages', project, 'package.json')
  
  try {
    const packageJson: PackageJson = JSON.parse(readFileSync(packageJsonPath, 'utf8'))
    
    if (packageJson.private) {
      console.log(`⏭️  Skipping private package ${project}`)
      return true
    }
    
    if (!packageJson.name) {
      console.error(`❌ No name found in package.json for ${project}`)
      return false
    }
    
    console.log(`📦 Publishing ${project}@${packageJson.version}...`)
    execSync(`npm publish dist/packages/${project} --access REDACTED_SECRET`, { 
      stdio: 'inherit',
      env: {
        ...process.env,
        NPM_TOKEN: process.env.NPM_TOKEN
      }
    })
    console.log(`✅ Successfully published ${project}`)
    return true
  } catch (error) {
    console.error(`❌ Failed to publish ${project}:`, error)
    return false
  }
}

function main() {
  if (!process.env.NPM_TOKEN) {
    console.error('❌ NPM_TOKEN environment variable is required')
    process.exit(1)
  }
  
  const affectedProjects = getAffectedProjects()
  
  if (affectedProjects.length === 0) {
    console.log('📭 No affected projects found')
    return
  }
  
  console.log(`🎯 Found ${affectedProjects.length} affected projects: ${affectedProjects.join(', ')}`)
  
  let successCount = 0
  let failCount = 0
  
  for (const project of affectedProjects) {
    const buildSuccess = buildProject(project)
    
    if (buildSuccess) {
      const publishSuccess = publishProject(project)
      if (publishSuccess) {
        successCount++
      } else {
        failCount++
      }
    } else {
      failCount++
    }
    
    console.log('---')
  }
  
  console.log(`\n📊 Summary: ${successCount} succeeded, ${failCount} failed`)
  
  if (failCount > 0) {
    process.exit(1)
  }
}

main()