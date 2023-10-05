package cone.customer.api.auctionservice.services.bidhandlingservices;

import cone.customer.api.auctionservice.entity.Bid;
import cone.customer.api.auctionservice.model.LiveBidModel;
import cone.customer.api.auctionservice.repository.BidRepository;
import cone.customer.utils.shared.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

@Component
public class BidAsyncServices {

    @Autowired
    BidRepository bidRepository;

    @Async
    public void insertRejectBid(LiveBidModel bidModel, Utils.BID_TYPE bidType, String message){

        Date bidSubmissionDate;
        if (bidModel.getBidDate() != null) {
            try {
                bidSubmissionDate = Date.from(Instant.parse(bidModel.getBidDate()));
            }catch (Exception e){
                bidSubmissionDate = null;
            }
        }else{
            bidSubmissionDate = null;
        }

        Bid bid = new Bid();
        bid.setStockId(bidModel.getStockId());
        bid.setCustomerId(bidModel.getCustomerId());
        bid.setAmount(bidModel.getAmount());
        bid.setQuantity(bidModel.getQuantity());
        bid.setBidStatus(bidType);
        bid.setBidStatusDesc(message);
        bid.setCreatedAt(new Date());
        bid.setBidSubmissionDate(bidSubmissionDate);
        bid.setBidReceivedDate(bidModel.getTimeOfBid());
        bid.setBidDeskNo(bidModel.getBid_desk_no());
        bid.setBidType("BID");
        bid.setBidTypeCd("BIDD");
        bid.setApprovedYn("N");
        this.bidRepository.save(bid);
    }
}
