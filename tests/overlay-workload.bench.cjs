'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict'),{fixture}=require('./overlay-fixture.cjs');
const before=fs.readFileSync('tests/fixtures/overlay-guard-review18.js','utf8');
const after=fs.readFileSync('app/src/main/assets/ja_overlay_guard/guard.js','utf8');
function measure(source,changing){const f=fixture(source,{size:3000});f.enable(true);
 for(let i=0;i<60;i++){if(changing){const text=f.body.children[0].childNodes[0];text.textContent='Time '+i;f.mutation([{type:'characterData',target:text}]);}f.advance(1000);}
 return {textNodesVisited:f.textReads,maxTextNodesPerTask:f.maxTaskTextReads};}
const results={method:'Node VM deterministic DOM fixture, 3,000 text nodes, simulated 60 seconds. Work counts only; not device CPU, FPS or decoding performance.',scenarios:[]};
for(const changing of [false,true]){const old=measure(before,changing),current=measure(after,changing);const reductionPercent=+(100*(1-current.textNodesVisited/old.textNodesVisited)).toFixed(1);
 assert(reductionPercent>60);assert(current.maxTextNodesPerTask<=160);
 results.scenarios.push({name:changing?'one text mutation per second':'idle page',review18:old,review19:current,reductionPercent});}
console.log(JSON.stringify(results,null,2));
