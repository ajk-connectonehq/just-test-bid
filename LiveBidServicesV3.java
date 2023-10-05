package cone.customer.api.auctionservice.services.bidhandlingservices;


import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.connect1.coreusercontext.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import cone.customer.api.auctionservice.customexceptions.*;
import cone.customer.api.auctionservice.entity.*;
import cone.customer.api.auctionservice.eventpublishers.BidPlacedEventPublisher;
import cone.customer.api.auctionservice.model.LiveBidModel;
import cone.customer.api.auctionservice.monitoring.LogExecutionTime;
import cone.customer.api.auctionservice.repository.*;
import cone.customer.api.auctionservice.services.*;
import cone.customer.utils.models.CommonResponseModel;
import cone.customer.utils.shared.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;


@Service
@Slf4j
public class LiveBidServicesV3 {


    @Autowired
    BidValidator bidValidator;

    @Autowired
    BidProcessor bidProcessor;


    @Autowired
    StockRepository stockRepository;

    @Transactional
    public ResponseEntity<CommonResponseModel> init(LiveBidModel bidModel, Boolean isAutobid) throws Exception {
        try {
            Optional<Stock> optionalStock = stockRepository.findById(bidModel.getStockId());
            if (optionalStock.isPresent()) {
                Stock stock = optionalStock.get();
                ResponseEntity<CommonResponseModel> validateResponse = bidValidator.validate(true, bidModel, isAutobid, stock);
                if(validateResponse != null){
                    return validateResponse;
                }
                Bid bid = bidProcessor.process(bidModel, stock);
                return ResponseEntity.status(HttpStatus.OK).body(new CommonResponseModel(200, "Bid placed successfully with bidId: " + bid.getId()));
            }else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new CommonResponseModel(404, "Stock not found"));
            }
        }
        catch (Exception e) {
            log.error("Exception occurred while placing bid: " + e.getMessage());
            throw e;
        }
    }


}
