/* Shared, cancellable menu transaction. No seeks, reloads or decoder changes. */
var JaQualityActivation = (() => {
  function create({open,find,focus,activate,close,mask,valid,report,
    wait=ms=>new Promise(resolve=>setTimeout(resolve,ms))}) {
    let generation=0,pending=null;
    function cancel(){generation++;mask(false);}
    function run(label,identity){
      if(pending)return pending;
      const token=++generation;
      pending=(async()=>{
        let owned=false;
        const check=()=>{if(token!==generation||!valid(identity))throw new Error('QUALITY_ACTIVATION_CANCELLED');};
        try{
          check();mask(true);owned=open();
          await wait(800);check();
          let row=find(label);if(!row)throw new Error('QUALITY_ROW_NOT_READY');
          focus(row);report('QUALITY_ROW_FOCUSED',{quality:label});
          await wait(300);check();
          row=find(label);if(!row)throw new Error('QUALITY_ROW_REPLACED');
          activate(row);
          report('QUALITY_ROW_ACTIVATED',{quality:label,openedByJA:owned});
          await wait(1800);check();
        }finally{
          try{if(owned)close();}finally{mask(false);}
        }
      })().finally(()=>{pending=null;});
      return pending;
    }
    return {run,cancel};
  }
  return {create};
})();
if(typeof module!=='undefined')module.exports=JaQualityActivation;
