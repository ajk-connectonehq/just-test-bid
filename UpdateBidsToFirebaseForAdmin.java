package cone.customer.api.auctionservice.services.bidhandlingservices;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.connect1.coreusercontext.UserContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.database.FirebaseDatabase;
import cone.customer.api.auctionservice.customexceptions.BidProcessingFailedException;
import cone.customer.api.auctionservice.entity.Bid;
import cone.customer.api.auctionservice.entity.Customer;
import cone.customer.api.auctionservice.helper.SpringEnvironmentHelper;
import cone.customer.api.auctionservice.model.AdminBidQueueData;
import cone.customer.api.auctionservice.model.LiveBidModel;
import cone.customer.api.auctionservice.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;

@Component
@Slf4j
public class UpdateBidsToFirebaseForAdmin {

    private static final String LIVE_AUCTION = "/live_auction/1";


    String tenantId;

    @Value("${env}")
    private String env;

    @Value("${sqs.url}")
    private String sqsURL;

    @Value("${aws.accessKey}")
    private String awsAccessKey;

    @Value("${aws.secretKey}")
    private String awsSecretKey;


    @Autowired
    CustomerRepository customerRepository;

    private AmazonSQS amazonSQS;


   @Async
    public void process(LiveBidModel bidModel, Bid bid) throws BidProcessingFailedException, JsonProcessingException {

        System.out.println("Inside UpdateBidsToFirebaseForAdmin");

        log.debug("1. Starting update admin Bids into Firebase of stock_id " + bidModel.getStockId() + " of bidder" +
                bidModel.getBidderName() + " with amount " + bidModel.getAmount());

        Boolean queueAdminBidsToFirebase = Boolean.FALSE;

        if (SpringEnvironmentHelper.isLocalEnvironment())
            tenantId = "AGRI_CARD_VGCP_1";
        else
            tenantId = UserContext.getTenantId();

        AWSCredentialsProvider awsCredentialsProvider = new AWSStaticCredentialsProvider(
                new BasicAWSCredentials(awsAccessKey, awsSecretKey));

        this.amazonSQS = AmazonSQSClientBuilder.standard().withCredentials(awsCredentialsProvider).build();

        Optional<Customer> customer = customerRepository.findById(bidModel.getCustomerId());
        if (customer.isPresent()) {
            bidModel.setBidderName(customer.get().getUName() + " (" + customer.get().getUVendorName()+")");
        }else{
            throw new BidProcessingFailedException("Customer not found for id " + bidModel.getCustomerId());
        }
        log.debug("Starting update admin Bids into Firebase of stock_id " + bidModel.getStockId() + " of bidder" +
                bidModel.getBidderName() + " with amount " + bidModel.getAmount());

        Map<String, Object> mapBid = createBidDataForFirebaseForAdmin(bidModel, bid);

        String firebaseNode = tenantId + LIVE_AUCTION;
        String stockIdString = bidModel.getStockId().toString();

        AdminBidQueueData adminBidQueueData = new AdminBidQueueData();
        adminBidQueueData.setMapBid(mapBid);
        adminBidQueueData.setFirebaseNode(firebaseNode);
        adminBidQueueData.setStockId(bidModel.getStockId());
        adminBidQueueData.setTenantId(tenantId);

        if (queueAdminBidsToFirebase) {
            log.debug("Adding bid to queue");
            addToQueue(adminBidQueueData);
        } else {
            FirebaseDatabase.getInstance().getReference(firebaseNode).child("bids/" + stockIdString)
                    .push().setValueAsync(mapBid);
        }



        log.debug("Completed update admin Bids into Firebase of stock_id " + bidModel.getStockId() + " of bidder" + bidModel.getBidderName() + " with amount " + bidModel.getAmount());
    }

    private Map<String, Object> createBidDataForFirebaseForAdmin(LiveBidModel bidModel, Bid bid) {

        if (SpringEnvironmentHelper.isLocalEnvironment())
            tenantId = "AGRI_CARD_VGCP_1";
        else
            tenantId = UserContext.getTenantId();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        Map<String, Object> mapBid = new HashMap<>();
        mapBid.put("bid_id", bid.getId());
        mapBid.put("stock_id", bid.getStockId());
        mapBid.put("price", bid.getAmount());
        mapBid.put("bidding_date", String.valueOf(bidModel.getBidDate()));
        mapBid.put("bidder_name", bidModel.getBidderName());
        mapBid.put("bid_submission_date",dateFormat.format(new Date()));
        mapBid.put("bid_received_date", dateFormat.format(new Date()));
        mapBid.put("approved_yn", bid.getApprovedYn());
        mapBid.put("buyer_id", bidModel.getCustomerId());
        mapBid.put("bid_desk_no", bidModel.getBid_desk_no());

        return mapBid;
    }

    private void addToQueue(AdminBidQueueData adminBidQueueData) throws JsonProcessingException {

        log.debug("Adding bid to queue");

        if (SpringEnvironmentHelper.isLocalEnvironment())
            tenantId = "AGRI_CARD_VGCP_1";
        else
            tenantId = UserContext.getTenantId();

        String queueURL = sqsURL + tenantId + "_" + env + "_rejected.fifo";

        log.debug("queueURL: " + queueURL);


        SendMessageRequest messageRequest = new SendMessageRequest();
        messageRequest.setMessageBody(new ObjectMapper().writeValueAsString(adminBidQueueData));
        messageRequest.setQueueUrl(queueURL);
        messageRequest.setMessageGroupId("bids-for-admin");

        // getQueue(amazonSQS, bidModel.getOrgId());
        amazonSQS.sendMessage(messageRequest);

        log.debug("Added bid to queue");
    }

    public void FirebaseInsertForAdminBidsFromQueue(AdminBidQueueData adminBidQueueData) throws JsonProcessingException {
        FirebaseDatabase.getInstance().getReference(adminBidQueueData.getFirebaseNode()).child("bids/" + adminBidQueueData.getStockId())
                .push().setValueAsync(adminBidQueueData.getMapBid());
    }
}
