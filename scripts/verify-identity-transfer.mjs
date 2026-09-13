#!/usr/bin/env node
import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
const [source='http://127.0.0.1:18877',target='http://127.0.0.1:18878',sourceOrigin='https://stealth.axxium.promethean.rest',targetOrigin='https://yoga.axxium.promethean.rest']=process.argv.slice(2);
let checks=0;
async function request(base,path,body,cookie) {
 const response=await fetch(base+path,{method:body?'POST':'GET',headers:{...(body?{'Content-Type':'application/json'}:{}),...(cookie?{Cookie:cookie}:{})},body:body?JSON.stringify(body):undefined});
 return {status:response.status,body:await response.json(),cookie:response.headers.get('set-cookie')?.split(';')[0]};
}
function status(r,expected){assert.equal(r.status,expected,JSON.stringify({status:r.status,error:r.body.error}));checks++;return r;}
const password=randomBytes(24).toString('base64url');
const email=`transfer-check-${Date.now()}@example.test`;
status(await request(source,'/health'),200);
status(await request(target,'/health'),200);
const registered=status(await request(source,'/api/auth/signup',{email,password,display_name:'Transfer verification'}),200);
const actor=registered.body.actor;
assert.ok(actor.id);checks++;
status(await request(source,'/api/identity/export',{password,recipient:targetOrigin}),401);
status(await request(source,'/api/identity/export',{password:'incorrect-passphrase',recipient:targetOrigin},registered.cookie),401);
status(await request(source,'/api/identity/export',{password,recipient:'https://untrusted.example'},registered.cookie),400);
const exported=status(await request(source,'/api/identity/export',{password,recipient:targetOrigin},registered.cookie),200);
const token=exported.body.transfer;assert.ok(token);checks++;
const payload=JSON.parse(Buffer.from(token.split('.')[1],'base64url'));
assert.equal(payload.aud,targetOrigin);assert.equal(payload.iss,sourceOrigin);checks+=2;
for(const key of ['password','password_hash','capabilities','roles']){assert.equal(payload[key],undefined);checks++;}
status(await request(source,'/api/identity/import',{transfer:token,password}),401);
const pieces=token.split('.');pieces[1]=Buffer.from(JSON.stringify({...payload,email:'forged@example.test'})).toString('base64url');
status(await request(target,'/api/identity/import',{transfer:pieces.join('.'),password}),401);
const recipientPassword=randomBytes(24).toString('base64url');
const imported=status(await request(target,'/api/identity/import',{transfer:token,password:recipientPassword}),200);
assert.equal(imported.body.actor.id,actor.id);assert.equal(imported.body.actor.origin_issuer,sourceOrigin);checks+=2;
status(await request(target,'/api/identity/import',{transfer:token,password:recipientPassword}),409);
status(await request(target,'/api/auth/logout',{},imported.cookie),200);
status(await request(target,'/api/auth/me',undefined,imported.cookie),401);
status(await request(target,'/api/auth/login',{email,password}),401);
const login=status(await request(target,'/api/auth/login',{email,password:recipientPassword}),200);
assert.equal(login.body.actor.id,actor.id);checks++;
const key=status(await request(source,'/.well-known/axxium-identity'),200).body.key;
assert.equal(key.d,undefined);checks++;
console.log(JSON.stringify({status:'passed',assertions:checks,source:sourceOrigin,recipient:targetOrigin,identity_preserved:true,replay_rejected:true,independent_password:true}));
