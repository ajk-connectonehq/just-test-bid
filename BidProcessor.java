package cone.customer.api.auctionservice.services.bidhandlingservices;

import com.amazonaws.services.sqs.AmazonSQS;
import com.connect1.coreusercontext.UserContext;
import com.github.javafaker.Bool;
import com.google.firebase.database.FirebaseDatabase;
import cone.customer.api.auctionservice.customexceptions.BidProcessingFailedException;
import cone.customer.api.auctionservice.entity.*;
import cone.customer.api.auctionservice.enums.BidType;
import cone.customer.api.auctionservice.eventpublishers.BidPlacedEventPublisher;
import cone.customer.api.auctionservice.helper.SpringEnvironmentHelper;
import cone.customer.api.auctionservice.model.LiveBidModel;
import cone.customer.api.auctionservice.repository.*;
import cone.customer.api.auctionservice.services.*;
import cone.customer.utils.shared.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
public class BidProcessor {

    @Autowired
    BidRepository bidRepository;

    @Autowired
    private  HighestBidRepository highestBidRepository;
    @Autowired
    private BidPersister bidPersister;

    @Autowired
    AsyncUpdator asyncProcessor;

    @Autowired
    AuctionStatusRepository auctionStatusRepository;

    @Autowired
    private UpdateExpectedPrice updateExpectedPrice;

    @Autowired
    private AutoBidService autoBidService;
    @Autowired
    private StockActiveBidderService stockActiveBidderService;



    @Autowired
    private BidPlacedEventPublisher bidPlacedEventPublisher;


    @Autowired
    UpdateBidsToFirebaseForAdmin updateBidsToFirebaseForAdmin;

    String tenantId;
    private static final String LIVE_CUST = "/1/highest_bids/";
    private static final String FINAL_BID = "/finalbid/";
    private static final String LIVE_AUCTION = "/live_auction/1";
    //create constructor for class

    public Bid process(LiveBidModel bidModel, Stock stock) throws Exception {

        if (SpringEnvironmentHelper.isLocalEnvironment())
            tenantId = "AGRI_CARD_VGCP_1";
        else
            tenantId = UserContext.getTenantId();

        updateHighestBid(bidModel);
        Bid bid = updateBidTableAsAccepted(bidModel);

        //Firebase write is kept after DB update to avoid write to firebase if DB update fails
        updateFireBaseHighestBidNode(bidModel);

        //Update timestamp when auction is stopped
        updateLatestTimestampWhenAuctionIsStopped(bidModel);

        //Async processing of bid
        updateBidsToFirebaseForAdmin.process(bidModel, bid);
        //updateExpectedPrice.init(bidModel.getStockId());
        autoBidService.init(bidModel.getStockId(), bidModel.getAmount(), bidModel.getCustomerId(), "Live");
        stockActiveBidderService.update(stock, true);
        this.bidPlacedEventPublisher.publish(stock,bid, BidType.ONLINE);

        return bid;
    }



    public void updateHighestBid(LiveBidModel bidModel) throws BidProcessingFailedException {
        try {
            HighestBid highestBid = highestBidRepository.getByStockId(bidModel.getStockId());

            highestBid.setStockId(bidModel.getStockId());
            highestBid.setQuantity(bidModel.getQuantity());
            highestBid.setCustomerId(bidModel.getCustomerId());
            highestBid.setAmount(bidModel.getAmount());

            if (highestBid.getCreatedAt() == null) {
                highestBid.setCreatedAt(new Date());
            } else {
                highestBid.setModifiedAt(new Date());
                highestBid.setUpdatedAt(new Date());

            }

            saveHighestBid(highestBid);
        } catch (Exception exp) {
            throw new BidProcessingFailedException("Insertion of highest bid failed for stock: " + bidModel.getStockId() + " with error: " + exp.getMessage(), exp);
        }
    }

    private void saveHighestBid(HighestBid highestBid) {
        boolean enableRawQuery = true;

        if (enableRawQuery) {
            highestBidRepository.updateHighestBidsForStockInLiveBid(highestBid.getAmount(), highestBid.getCustomerId(), highestBid.getStockId(),highestBid.getQuantity());
        } else {
            highestBidRepository.save(highestBid);
        }

    }




    private void updateFirebaseReference(String node, Map<String, Object> data) {
        FirebaseDatabase.getInstance().getReference(node).updateChildrenAsync(data);
    }

    public Bid updateBidTableAsAccepted(LiveBidModel bidModel) throws BidProcessingFailedException {
        try {

            Integer bidId;

            Date bidSubmissionDate;
            Date bidreceivedDate = new Date();

            if (bidModel.getBidDate() != null) {
                try {
                    bidSubmissionDate = Date.from(Instant.parse(bidModel.getBidDate()));
                }catch (Exception e){
                    bidSubmissionDate = new Date();

                }
            }else{
                bidSubmissionDate = new Date();

            }

            Bid bid = new Bid();
            bid.setStockId(bidModel.getStockId());
            bid.setCustomerId(bidModel.getCustomerId());
            bid.setAmount(bidModel.getAmount());
            bid.setQuantity(bidModel.getQuantity());
            bid.setBidStatus(Utils.BID_TYPE.AC);
            bid.setBidStatusDesc("Accepted");
            bid.setCreatedAt(new Date());
            bid.setBidSubmissionDate(bidSubmissionDate);
            bid.setBidReceivedDate(bidreceivedDate);
            bid.setBidDeskNo(bidModel.getBid_desk_no());
            bid.setBidType("BID");
            bid.setBidTypeCd("BIDD");
            bid.setApprovedYn("N");
            this.bidRepository.save(bid);

            return bid;

        } catch (Exception exp) {
            throw new BidProcessingFailedException("Insertion of bid failed for stock: " + bidModel.getStockId() + " with error: " + exp.getMessage(), exp);
        }
    }

    public void updateFireBaseHighestBidNode(LiveBidModel bidModel) throws BidProcessingFailedException {
        log.debug("Starting update highestbids" + bidModel.getAmount() +" of customer_id " + bidModel.getCustomerId() + " into Firebase");

        try {

            if (SpringEnvironmentHelper.isLocalEnvironment())
                tenantId = "AGRI_CARD_VGCP_1";
            else
                tenantId = UserContext.getTenantId();

            Map<String, Object> highestBidData = createHighestBidData(bidModel);

            String liveAuctionNode = tenantId + LIVE_AUCTION;
            String liveCustNode = "live_cust/" + tenantId + LIVE_CUST;

            updateFirebaseReference(liveAuctionNode + "/highest_bids/" + bidModel.getStockId(), highestBidData);
            updateFirebaseReference(liveCustNode + bidModel.getStockId(), highestBidData);

            log.debug("Completed update highestbids" + bidModel.getAmount() +" of customer_id " + bidModel.getCustomerId() + " into Firebase");
        } catch (Exception e) {
            throw new BidProcessingFailedException("Failed to process the bid: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> createHighestBidData(LiveBidModel bidModel) {
        Map<String, Object> highestBidData = new HashMap<>();
        highestBidData.put("customer_id", bidModel.getCustomerId());
        highestBidData.put("highest_bid", bidModel.getAmount());
        return highestBidData;
    }

    private Map<String, Object> createTimestampData() {
        Map<String, Object> timestampData = new HashMap<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        timestampData.put("timestamp", dateFormat.format(new Date()));
        return timestampData;
    }


    public void updateLatestTimestampWhenAuctionIsStopped(LiveBidModel bidModel) throws BidProcessingFailedException {

        System.out.println("updateLatestTimestampWhenAuctionIsStopped");
        try {

            if (SpringEnvironmentHelper.isLocalEnvironment())
                tenantId = "AGRI_CARD_VGCP_1";
            else
                tenantId = UserContext.getTenantId();

            Map<String, Object> timestampData = createTimestampData();

            String liveAuctionNode = tenantId + LIVE_AUCTION;

            List<AuctionStatusLog> auctionStatus = auctionStatusRepository.findByStock(bidModel.getStockId());
            if (!auctionStatus.isEmpty() && (auctionStatus.get(auctionStatus.size() - 1).getStockStatus().getName()
                    .equalsIgnoreCase("AuctionStopped"))) {
                updateFirebaseReference(liveAuctionNode + FINAL_BID, timestampData);
                updateFirebaseReference("live_cust/" + tenantId + FINAL_BID, timestampData);
            }

      } catch (Exception e) {
            throw new BidProcessingFailedException("Failed to process the bid: " + e.getMessage(), e);
        }
    }


}
