package cone.customer.api.auctionservice.services.bidhandlingservices;

import com.connect1.coreusercontext.UserContext;
import cone.customer.api.auctionservice.customexceptions.*;
import cone.customer.api.auctionservice.entity.*;
import cone.customer.api.auctionservice.helper.SpringEnvironmentHelper;
import cone.customer.api.auctionservice.model.LiveBidModel;
import cone.customer.api.auctionservice.repository.*;
import cone.customer.api.auctionservice.services.SettingsService;
import cone.customer.utils.models.CommonResponseModel;
import cone.customer.utils.shared.Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Component
public class BidValidator {
    
    @Autowired
    BidAsyncServices bidAsyncServices;

    @Autowired
    SettingsService settingsService;

    @Autowired
    StockRepository stockRepository;

    @Autowired
    MvtLaBuyersDAO mvtLaBuyersDAO;

    @Autowired
    BidRepository bidRepository;

    @Autowired
    HighestBidRepository highestBidRepository;

    @Autowired
    StockItemRepository stockItemRepository;

    @Autowired
    CustomerMarginRepository customerMarginRepository;

    /*
     * This method validates the bid request and throws appropriate exception if any validation fails
     * @param isOnline - true if the bid is placed from online, false if the bid is placed from offline
     * @param bidModel - the bid request model with customerId, stockId, amount etc
     * @param isAutobid - true if the bid is placed from autobid service, false if the bid is placed from live bid
     * @return Stock - the stock entity for the given stock id
     */
    public ResponseEntity<CommonResponseModel> validate(Boolean isOnline, LiveBidModel bidModel, Boolean isAutobid, Stock stock) {

        ResponseEntity<CommonResponseModel> responseModelResponseEntity = checkBidderIsNotSeller(bidModel, isAutobid, stock);
        if (responseModelResponseEntity != null) {
            return responseModelResponseEntity;
        }

        Optional<MvtLaBuyers> mvtLaBuyers = mvtLaBuyersDAO.findById(bidModel.getCustomerId());
        if (!mvtLaBuyers.isPresent()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(422, "Customer ID not found in records!! Please provide a valid customer ID"));
        }
        MvtLaBuyers buyer = mvtLaBuyers.get();
        ResponseEntity<CommonResponseModel> checkedBuyerLimit= checkBuyerLimit(bidModel,buyer);
        if (checkedBuyerLimit != null) {
            return checkedBuyerLimit;
        }
        ResponseEntity<CommonResponseModel> checkedMargin=checkMargin(bidModel, isAutobid);
        if (checkedMargin != null) {
            return checkedMargin;
        }
        ResponseEntity<CommonResponseModel> checkedBidIsHigher=checkBidIsHigher(bidModel);
        if (checkedBidIsHigher != null) {
            return checkedBidIsHigher;
        }
        ResponseEntity<CommonResponseModel> checkStockAuctionInProgressResponse = checkStockAuctionInProgress(bidModel.getStockId(),bidModel);
        if(checkStockAuctionInProgressResponse != null){
            return checkStockAuctionInProgressResponse;
        }
        return null;
    }

    /*
     * This method checks if auction is in progress for the given stock id by checking the stock status
     * if the stock status is not AuctionStarted or AuctionStopped, then the auction is not in progress
     * @param stockId - the stock id for which the auction is to be checked
     * @return void
     */
    private ResponseEntity<CommonResponseModel> checkStockAuctionInProgress(Long stockId,LiveBidModel bidModel) {
        String stockStatus = stockRepository.findStockStatusById(stockId);
        if (!stockStatus.equalsIgnoreCase("AuctionStarted") && !stockStatus.equalsIgnoreCase("AuctionStopped")) {
            bidAsyncServices.insertRejectBid(bidModel,Utils.BID_TYPE.ER,"Auction Ended");
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(422, "This lot is not in running state, Admin must have closed the auction for this lot"));
        }
        return null;
    }



    /*
     * This method checks if buyer limit exceeds for the given customer id by checking the bid amount and quantity
     * Then it checks if the bid amount is higher than the current highest bid
     * @param bidModel - the bid request model with customerId, stockId, amount etc
     * @return void
     */

    private ResponseEntity<CommonResponseModel> checkBuyerLimit(LiveBidModel bidModel,MvtLaBuyers mvtLaBuyers){

        Integer alreadyBidAmount = highestBidRepository.getAmountByCustomerIdAndCreatedAt(
                bidModel.getCustomerId(), bidModel.getStockId(), new Date());
        alreadyBidAmount = alreadyBidAmount == null ? 0 : alreadyBidAmount;

        boolean buyerLimitAvailable;
        if (settingsService.marginEnabled()) {
            buyerLimitAvailable = true; // Skip buyer limit check if margin is enabled
        } else {
            buyerLimitAvailable = (mvtLaBuyers.getBidMaxAmount() - alreadyBidAmount) > (bidModel.getAmount() * bidModel.getQuantity());
        }
        if (!buyerLimitAvailable) {
            bidAsyncServices.insertRejectBid(bidModel,Utils.BID_TYPE.OL,"Low Bid limit");
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(102, "ERROR - Low Bid limit, please contact the Admin, your remaining buying limit is " +
                    (mvtLaBuyers.getBidMaxAmount() - alreadyBidAmount)));

        }
        return null;
    }

    /*
     * This method checks if current highest bid is lower than or equal to the bid amount
     * @param bidModel - the bid request model with customerId, stockId, amount etc
     * @return void
     */
    private ResponseEntity<CommonResponseModel> checkBidIsHigher(LiveBidModel bidModel){
        Long bidderId = bidModel.getCustomerId();
        HighestBid prevHighestBid = highestBidRepository.getByStockId(bidModel.getStockId());
        if (prevHighestBid != null) {
            if (prevHighestBid.getAmount() > bidModel.getAmount()) {
                bidAsyncServices.insertRejectBid(bidModel,Utils.BID_TYPE.LB,"Low Bid");
                return ResponseEntity.status(HttpStatus.OK).body(new CommonResponseModel(200, "Bid placed successfully (LB)"));

                //return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(422, "Your bid must be higher than the current highest bid!"));
            } else if (prevHighestBid.getAmount().equals(bidModel.getAmount())) {
                bidAsyncServices.insertRejectBid(bidModel,Utils.BID_TYPE.SB,"Same Bid");
                return ResponseEntity.status(HttpStatus.OK).body(new CommonResponseModel(200, "Bid placed successfully (SB)"));

                //return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(422, "Your bid must be higher than the current bid! Bidding the same amount is not allowed."));
            } else if (prevHighestBid.getCustomerId().equals(bidderId)) {

                bidAsyncServices.insertRejectBid(bidModel,Utils.BID_TYPE.SB,"Same Bid");

                return ResponseEntity.status(HttpStatus.OK).body(new CommonResponseModel(200, "Bid placed successfully (SB)"));

                //return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(422, "You are the current highest bidder."));

            }
        }
        return null;
    }

    /*
     * This method checks if the bidder is not the seller of the stock
     * @param bidModel - the bid request model with customerId, stockId, amount etc
     * @param isAutobid - true if the bid is placed from autobid service, false if the bid is placed from live bid
     * @param stock - the stock entity for the given stock id
     * @return void
     */
    private ResponseEntity<CommonResponseModel> checkBidderIsNotSeller(LiveBidModel bidModel, Boolean isAutobid, Stock stock){

        String allow_seller_biddding_yn = settingsService.getByKeyName1("allow_seller_biddding_yn");

        if(allow_seller_biddding_yn.equalsIgnoreCase("N")){
            Long sellerId = stock.getCustomerId();
            Long bidderId = 0L;

            if (isAutobid || SpringEnvironmentHelper.isLocalEnvironment()) {
                bidderId = bidModel.getCustomerId();
            } else {
                bidderId = Long.valueOf(UserContext.getCustomerId());
            }

            if (sellerId.equals(bidderId)) {
                bidAsyncServices.insertRejectBid(bidModel,Utils.BID_TYPE.ER,"Seller of Stock");
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(422, "You are the seller of this stock -" + stock.getId()));
            }
        }

        return null;
    }

    /*
     * This method checks if the bidder has enough margin to place the bid
     * @param bidModel - the bid request model with customerId, stockId, amount etc
     * @param isAutobid - true if the bid is placed from autobid service, false if the bid is placed from live bid
     */
    private ResponseEntity<CommonResponseModel> checkMargin(LiveBidModel bidModel, Boolean isAutobid)  {
        if (settingsService.marginEnabled()) {
            Long bidderId;
            if(!SpringEnvironmentHelper.isLocalEnvironment()){
                 bidderId = isAutobid ? bidModel.getCustomerId() : Long.valueOf(UserContext.getCustomerId());
            }else {
                 bidderId =  bidModel.getCustomerId();
            }

            Optional<StockItem> optionalStockItem = stockItemRepository.findByStockId(bidModel.getStockId());
            Optional<CustomerMargin> optionalCustomerMargin = customerMarginRepository.findById(Math.toIntExact(bidderId));

            if (optionalStockItem.isPresent() && optionalCustomerMargin.isPresent()) {
                Double availableMargin = optionalCustomerMargin.get().getCurrentMargin();
                Double marginPecentage = settingsService.marginPercentage();
                Double totalMarginRequired = (bidModel.getAmount() * optionalStockItem.get().getQuantity()) * (marginPecentage / 100);

                if (availableMargin < totalMarginRequired) {
                    bidAsyncServices.insertRejectBid(bidModel,Utils.BID_TYPE.ER,"Low Margin");
                    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(422, "Your total margin available is " + availableMargin + " & you bid requires a margin of " + totalMarginRequired + ". The difference is " + (totalMarginRequired - availableMargin)));
                }
            } else {
                bidAsyncServices.insertRejectBid(bidModel,Utils.BID_TYPE.ER,"Stock item/Customer Margin not found.");
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new CommonResponseModel(422, "Stock item or customer margin information not found."));
            }
        } else {
            return null;
        }
        return null;
    }

  
}
