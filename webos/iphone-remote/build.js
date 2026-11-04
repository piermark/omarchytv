#!/usr/bin/env node
'use strict';
const fs=require('fs'),path=require('path'),cp=require('child_process'),crypto=require('crypto'),os=require('os');
const src=__dirname,out=path.resolve(process.argv[2]||path.join(src,'../app/rooted/iphone-remote'));
if(!fs.existsSync(src+'/node_modules/hap-nodejs'))throw Error('Run npm ci --prefix webos/iphone-remote --omit=dev --ignore-scripts');
const stage=fs.mkdtempSync(path.join(os.tmpdir(),'omarchytv-remote-build-'));
try{
 for(const file of ['server.js','platform.js','manage.js','start','package.json','package-lock.json'])fs.copyFileSync(src+'/'+file,stage+'/'+file);
 fs.chmodSync(stage+'/start',0o755);
 fs.cpSync(src+'/node_modules',stage+'/node_modules',{recursive:true,filter:p=>!p.includes('/.bin')});
 fs.copyFileSync(path.resolve(src,'../../LICENSE'),stage+'/LICENSE');
 fs.mkdirSync(out,{recursive:true});
 // GNU tar creates deterministic archives; executable modes and dependency licences are retained.
 cp.execFileSync('tar',['--sort=name','--mtime=@0','--owner=0','--group=0','--numeric-owner','-C',stage,'-cf',out+'/bundle.tar','.']);
 const raw=fs.readFileSync(out+'/bundle.tar');fs.writeFileSync(out+'/bundle.tar.gz',require('zlib').gzipSync(raw,{level:9}));fs.unlinkSync(out+'/bundle.tar');
 fs.copyFileSync(src+'/manage.js',out+'/manage.js');
 const manifest={version:require('./package.json').version,sha256:crypto.createHash('sha256').update(fs.readFileSync(out+'/bundle.tar.gz')).digest('hex')};
 fs.writeFileSync(out+'/manifest.json',JSON.stringify(manifest,null,2)+'\n');
 console.log('Bundled iPhone Remote '+manifest.version+' ('+fs.statSync(out+'/bundle.tar.gz').size+' bytes)');
}finally{fs.rmSync(stage,{recursive:true,force:true});}
