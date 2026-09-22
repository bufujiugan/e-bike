package com.bike.server.pi;

import com.bike.server.production.ProductionFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PiExportWorker implements AutoCloseable {
    private final PiExportMapper mapper;
    private final PiDocumentService documents;
    private final ProductionFileStore files;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"pi-export-worker");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    public PiExportWorker(PiExportMapper mapper,PiDocumentService documents,ProductionFileStore files,ObjectMapper json,PlatformTransactionManager manager) {
        this.mapper=mapper;this.documents=documents;this.files=files;this.json=json;this.tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelay=3000) public void tick() {
        if(!running.compareAndSet(false,true))return;
        try {worker.submit(()->{try{for(int n=0;n<5&&processNext();n++);}catch(Exception error){LoggerFactory.getLogger(getClass()).warn("PI export worker interrupted by database/storage failure");}finally{running.set(false);}});}
        catch(RejectedExecutionException stopped){running.set(false);}
    }
    public boolean processNext() {
        var job=tx.execute(status->{Long id=mapper.next();if(id==null)return null;String token=UUID.randomUUID().toString();return mapper.claim(id,token)==1?mapper.get(id):null;});
        if(job==null)return false;
        try {
            var snapshot=json.readValue(job.snapshotJson(),PiView.class);
            if(snapshot.getDocumentRenderVersion()==null)snapshot.setDocumentRenderVersion("PI_V1");
            if(!java.util.Set.of("PI_V1","PI_V2").contains(snapshot.getDocumentRenderVersion()))throw new IOException("Unsupported saved renderer version");
            if(job.pdfKey()==null)save(job,"pdf",documents.pdf(snapshot));
            if(job.xlsxKey()==null)save(job,"xlsx",documents.excel(snapshot));
            mapper.ready(job.piId(),job.claimToken());
        }catch(Exception error){mapper.fail(job.piId(),job.claimToken(),"PI_EXPORT_GENERATION_FAILED");}
        return true;
    }
    private void save(PiExportJob job,String format,byte[] bytes)throws IOException {
        if(bytes.length>20*1024*1024)throw new IOException("PI export exceeds size limit");
        String key=files.key("pi-export-"+UUID.randomUUID()+"."+format);
        boolean writeComplete=false;
        try {
            files.write(key,new ByteArrayInputStream(bytes),bytes.length,"pdf".equals(format)?"application/pdf":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            writeComplete=true;
            if(mapper.saveFile(job.piId(),job.claimToken(),format,key,PiExportArchive.sha256(bytes),bytes.length)!=1)throw new IOException("PI export lease changed");
        }catch(Exception error) {
            // On an ambiguous SQL response, read the durable pointer before deleting a potentially committed file.
            try {var saved=mapper.get(job.piId());if(saved==null||(!key.equals(saved.pdfKey())&&!key.equals(saved.xlsxKey())))files.remove(key);}
            catch(Exception cleanup){LoggerFactory.getLogger(getClass()).warn("PI export orphan needs reconciliation: {} (written={})",key,writeComplete);}
            if(error instanceof IOException io)throw io;
            throw new IOException("PI export storage failed",error);
        }
    }
    @PreDestroy public void close(){worker.shutdown();}
}
