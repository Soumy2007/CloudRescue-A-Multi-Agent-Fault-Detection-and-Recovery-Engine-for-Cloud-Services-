package com.cloudrescue.experiment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ExperimentRunRepository extends JpaRepository<ExperimentRun, Long> {
    List<ExperimentRun> findByBatchId(String batchId);

    @Query("select e.batchId, max(e.createdAt), count(e) from ExperimentRun e group by e.batchId order by max(e.createdAt) desc")
    List<Object[]> listBatches();
}
